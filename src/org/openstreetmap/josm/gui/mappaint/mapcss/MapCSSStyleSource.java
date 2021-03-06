// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.gui.mappaint.mapcss;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.Color;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.openstreetmap.josm.Main;
import org.openstreetmap.josm.data.Version;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.gui.mappaint.Cascade;
import org.openstreetmap.josm.gui.mappaint.Environment;
import org.openstreetmap.josm.gui.mappaint.LineElemStyle;
import org.openstreetmap.josm.gui.mappaint.MultiCascade;
import org.openstreetmap.josm.gui.mappaint.Range;
import org.openstreetmap.josm.gui.mappaint.StyleKeys;
import org.openstreetmap.josm.gui.mappaint.StyleSetting;
import org.openstreetmap.josm.gui.mappaint.StyleSetting.BooleanStyleSetting;
import org.openstreetmap.josm.gui.mappaint.StyleSource;
import org.openstreetmap.josm.gui.mappaint.mapcss.Condition.KeyCondition;
import org.openstreetmap.josm.gui.mappaint.mapcss.Condition.KeyMatchType;
import org.openstreetmap.josm.gui.mappaint.mapcss.Condition.KeyValueCondition;
import org.openstreetmap.josm.gui.mappaint.mapcss.Condition.Op;
import org.openstreetmap.josm.gui.mappaint.mapcss.Condition.SimpleKeyValueCondition;
import org.openstreetmap.josm.gui.mappaint.mapcss.Selector.ChildOrParentSelector;
import org.openstreetmap.josm.gui.mappaint.mapcss.Selector.GeneralSelector;
import org.openstreetmap.josm.gui.mappaint.mapcss.Selector.OptimizedGeneralSelector;
import org.openstreetmap.josm.gui.mappaint.mapcss.parsergen.MapCSSParser;
import org.openstreetmap.josm.gui.mappaint.mapcss.parsergen.ParseException;
import org.openstreetmap.josm.gui.mappaint.mapcss.parsergen.TokenMgrError;
import org.openstreetmap.josm.gui.preferences.SourceEntry;
import org.openstreetmap.josm.io.CachedFile;
import org.openstreetmap.josm.tools.CheckParameterUtil;
import org.openstreetmap.josm.tools.LanguageInfo;
import org.openstreetmap.josm.tools.Utils;

public class MapCSSStyleSource extends StyleSource {

    /**
     * The accepted MIME types sent in the HTTP Accept header.
     * @since 6867
     */
    public static final String MAPCSS_STYLE_MIME_TYPES = "text/x-mapcss, text/mapcss, text/css; q=0.9, text/plain; q=0.8, application/zip, application/octet-stream; q=0.5";

    // all rules
    public final List<MapCSSRule> rules = new ArrayList<>();
    // rule indices, filtered by primitive type
    public final MapCSSRuleIndex nodeRules = new MapCSSRuleIndex();         // nodes
    public final MapCSSRuleIndex wayRules = new MapCSSRuleIndex();          // ways without tag area=no
    public final MapCSSRuleIndex wayNoAreaRules = new MapCSSRuleIndex();    // ways with tag area=no
    public final MapCSSRuleIndex relationRules = new MapCSSRuleIndex();     // relations that are not multipolygon relations
    public final MapCSSRuleIndex multipolygonRules = new MapCSSRuleIndex(); // multipolygon relations
    public final MapCSSRuleIndex canvasRules = new MapCSSRuleIndex();       // rules to apply canvas properties

    private Color backgroundColorOverride;
    private String css = null;
    private ZipFile zipFile;

    /**
     * This lock prevents concurrent execution of {@link MapCSSRuleIndex#clear() } /
     * {@link MapCSSRuleIndex#initIndex()} and {@link MapCSSRuleIndex#getRuleCandidates }.
     *
     * For efficiency reasons, these methods are synchronized higher up the
     * stack trace.
     */
    public static final ReadWriteLock STYLE_SOURCE_LOCK = new ReentrantReadWriteLock();

    /**
     * Set of all supported MapCSS keys.
     */
    public static final Set<String> SUPPORTED_KEYS = new HashSet<>();
    static {
        Field[] declaredFields = StyleKeys.class.getDeclaredFields();
        for (Field f : declaredFields) {
            try {
                SUPPORTED_KEYS.add((String) f.get(null));
                if (!f.getName().toLowerCase().replace("_", "-").equals(f.get(null))) {
                    throw new RuntimeException(f.getName());
                }
            } catch (IllegalArgumentException | IllegalAccessException ex) {
                throw new RuntimeException(ex);
            }
        }
        for (LineElemStyle.LineType lt : LineElemStyle.LineType.values()) {
            SUPPORTED_KEYS.add(lt.prefix + StyleKeys.COLOR);
            SUPPORTED_KEYS.add(lt.prefix + StyleKeys.DASHES);
            SUPPORTED_KEYS.add(lt.prefix + StyleKeys.DASHES_BACKGROUND_COLOR);
            SUPPORTED_KEYS.add(lt.prefix + StyleKeys.DASHES_BACKGROUND_OPACITY);
            SUPPORTED_KEYS.add(lt.prefix + StyleKeys.DASHES_OFFSET);
            SUPPORTED_KEYS.add(lt.prefix + StyleKeys.LINECAP);
            SUPPORTED_KEYS.add(lt.prefix + StyleKeys.LINEJOIN);
            SUPPORTED_KEYS.add(lt.prefix + StyleKeys.MITERLIMIT);
            SUPPORTED_KEYS.add(lt.prefix + StyleKeys.OFFSET);
            SUPPORTED_KEYS.add(lt.prefix + StyleKeys.OPACITY);
            SUPPORTED_KEYS.add(lt.prefix + StyleKeys.REAL_WIDTH);
            SUPPORTED_KEYS.add(lt.prefix + StyleKeys.WIDTH);
        }
    }

    private static class MergedRules implements Iterator<MapCSSRule>{
        private final ArrayList<Iterator<MapCSSRule>> rules;
        // Heap and head of each rule.
        private final MapCSSRule[] heap;
        private int heapSize;

        public MergedRules(ArrayList<Iterator<MapCSSRule>> rules) {
            this.rules = rules;
            heapSize = rules.size() - 1;
            this.heap = new MapCSSRule[heapSize + rules.size()];
            for (int i = heap.length - 1; i >= 0; i--) {
                // Pull the null values out.
                pullOutOf(i);
            }
        }

        @Override
        public boolean hasNext() {
            return heap[0] != null;
        }

        @Override
        public MapCSSRule next() {
            return pullOutOf(0);
        }

        private MapCSSRule pullOutOf(int i) {
            MapCSSRule next = heap[i];
            if (i < heapSize) {
                // Pull from two upper nodes.
                int nextLeft = i * 2 + 1;
                int nextRight = i * 2 + 2;
                if (heap[nextLeft] == null) {
                    heap[i] = pullOutOf(nextRight);
                } else if (heap[nextRight] == null) {
                    heap[i] = pullOutOf(nextLeft);
                } else {
                    if (heap[nextLeft].compareTo(heap[nextRight]) < 0) {
                        heap[i] = pullOutOf(nextLeft);
                    } else {
                        heap[i] = pullOutOf(nextRight);
                    }
                }
            } else {
                // pull from iterator.
                Iterator<MapCSSRule> it = rules.get(i - heapSize);
                heap[i] = it.hasNext() ? it.next() : null;
            }
            return next;
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * A collection of {@link MapCSSRule}s, that are indexed by tag key and value.
     *
     * Speeds up the process of finding all rules that match a certain primitive.
     *
     * Rules with a {@link SimpleKeyValueCondition} [key=value] are indexed by
     * key and value in a HashMap. Now you only need to loop the tags of a
     * primitive to retrieve the possibly matching rules.
     *
     * Rules with no SimpleKeyValueCondition in the selector have to be
     * checked separately.
     *
     * The order of rules gets mixed up by this and needs to be sorted later.
     */
    public static class MapCSSRuleIndex {
        /* all rules for this index */
        public final List<MapCSSRule> rules = new ArrayList<>();
        /* tag based index */
        public final Map<String,Map<String,ArrayList<MapCSSRule>>> index = new HashMap<>();
        public final Map<String,ArrayList<MapCSSRule>> keyIndex = new HashMap<>();
        /* rules without SimpleKeyValueCondition */
        public final ArrayList<MapCSSRule> remaining = new ArrayList<>();
        public final HashSet<MapCSSRule> remaining2 = new HashSet<>();

        public void add(MapCSSRule rule) {
            rules.add(rule);
        }

        /**
         * Initialize the index.
         *
         * You must own the write lock of STYLE_SOURCE_LOCK when calling this method.
         */
        public void initIndex() {
            for (MapCSSRule r: rules) {
                // find the rightmost selector, this must be a GeneralSelector
                Selector selRightmost = r.selector;
                while (selRightmost instanceof ChildOrParentSelector) {
                    selRightmost = ((ChildOrParentSelector) selRightmost).right;
                }
                OptimizedGeneralSelector s = (OptimizedGeneralSelector) selRightmost;
                if (s.conds == null) {
                    remaining.add(r);
                    remaining2.add(r);
                    System.out.println("No conds: " + r);
                    continue;
                }
                List<SimpleKeyValueCondition> sk = new ArrayList<>(Utils.filteredCollection(s.conds, SimpleKeyValueCondition.class));
                if (!sk.isEmpty()) {
                    SimpleKeyValueCondition c = sk.get(sk.size() - 1);
                    Map<String,ArrayList<MapCSSRule>> rulesWithMatchingKey = index.get(c.k);
                    if (rulesWithMatchingKey == null) {
                        rulesWithMatchingKey = new HashMap<>();
                        index.put(c.k, rulesWithMatchingKey);
                    }
                    ArrayList<MapCSSRule> rulesWithMatchingKeyValue = rulesWithMatchingKey.get(c.v);
                    if (rulesWithMatchingKeyValue == null) {
                        rulesWithMatchingKeyValue = new ArrayList<>();
                        rulesWithMatchingKey.put(c.v, rulesWithMatchingKeyValue);
                    }
                    rulesWithMatchingKeyValue.add(r);
                } else {
                    remaining2.add(r);
                    String key = findRequiredKey(s.conds);
                    if (key != null) {
                        ArrayList<MapCSSRule> rulesWithMatchingKey = keyIndex.get(key);
                        if (rulesWithMatchingKey == null) {
                            rulesWithMatchingKey = new ArrayList<>();
                            keyIndex.put(key, rulesWithMatchingKey);
                        }
                        rulesWithMatchingKey.add(r);
                    } else {
                        remaining.add(r);
                        System.out.println("No key: " + r);
                    }
                }
            }
            Collections.sort(remaining);

            for (Map<String, ArrayList<MapCSSRule>> e : index.values()) {
                for (ArrayList<MapCSSRule> rules : e.values()) {
                    Collections.sort(rules);
                }
            }
        }

        // Copied
        private static final Set<Op> NEGATED_OPS = EnumSet.of(Op.NEQ, Op.NREGEX);
        // Any key required for this.
        private String findRequiredKey(List<Condition> conds) {
            String key = null;
            for (Condition c : conds) {
                if (c instanceof KeyCondition) {
                    KeyCondition keyCondition = (KeyCondition) c;
                    if (!keyCondition.negateResult && (keyCondition.matchType == KeyMatchType.FALSE || keyCondition.matchType == KeyMatchType.TRUE || keyCondition.matchType == KeyMatchType.EQ || keyCondition.matchType == null)) {
                        key = keyCondition.label;
                    }
                } else if (c instanceof KeyValueCondition) {
                    KeyValueCondition keyValueCondition = (KeyValueCondition) c;
                    if (!NEGATED_OPS.contains(keyValueCondition)) {
                        key = keyValueCondition.k;
                    }
                }
            }
            return key;
        }

        /**
         * Get a subset of all rules that might match the primitive.
         * @param osm the primitive to match
         * @return a Collection of rules that filters out most of the rules
         * that cannot match, based on the tags of the primitive
         *
         * You must have a read lock of STYLE_SOURCE_LOCK when calling this method.
         */
        public Iterator<MapCSSRule> getRuleCandidates(OsmPrimitive osm) {
            ArrayList<Iterator<MapCSSRule>> ruleCandidates = new ArrayList<>();
            ruleCandidates.add(remaining.iterator());
            for (Map.Entry<String,String> e : osm.getKeys().entrySet()) {
                Map<String, ArrayList<MapCSSRule>> v = index.get(e.getKey());
                if (v != null) {
                    ArrayList<MapCSSRule> rs = v.get(e.getValue());
                    if (rs != null)  {
                        ruleCandidates.add(rs.iterator());
                    }
                }
                ArrayList<MapCSSRule> forKey = keyIndex.get(e.getKey());
                if (forKey != null) {
                    ruleCandidates.add(forKey.iterator());
                }
            }
            if (ruleCandidates.size() == 1) {
                return ruleCandidates.get(0);
            } else {
                System.out.println("Found iterators: " + ruleCandidates.size());
                return new MergedRules(ruleCandidates);
            }
        }

        private static final MapCSSRule[] ruleArray = new MapCSSRule[0];

        public MapCSSRule[] getRuleCandidatesOld(OsmPrimitive osm) {
            ArrayList<MapCSSRule> ruleCandidates = new ArrayList<>(remaining2);
            for (Map.Entry<String,String> e : osm.getKeys().entrySet()) {
                Map<String, ArrayList<MapCSSRule>> v = index.get(e.getKey());
                if (v != null) {
                    ArrayList<MapCSSRule> rs = v.get(e.getValue());
                    if (rs != null)  {
                        ruleCandidates.addAll(rs);
                    }
                }
//                ArrayList<MapCSSRule> forKey = keyIndex.get(e.getKey());
//                if (forKey != null) {
//                    ruleCandidates.addAll(forKey);
//                }
            }
            MapCSSRule[] result = ruleCandidates.toArray(ruleArray);
            Arrays.sort(result);
            return result;
        }

        /**
         * Clear the index.
         *
         * You must own the write lock STYLE_SOURCE_LOCK when calling this method.
         */
        public void clear() {
            rules.clear();
            index.clear();
            remaining.clear();
            keyIndex.clear();
            remaining2.clear();
        }
    }

    public MapCSSStyleSource(String url, String name, String shortdescription) {
        super(url, name, shortdescription);
    }

    public MapCSSStyleSource(SourceEntry entry) {
        super(entry);
    }

    /**
     * <p>Creates a new style source from the MapCSS styles supplied in
     * {@code css}</p>
     *
     * @param css the MapCSS style declaration. Must not be null.
     * @throws IllegalArgumentException thrown if {@code css} is null
     */
    public MapCSSStyleSource(String css) throws IllegalArgumentException{
        super(null, null, null);
        CheckParameterUtil.ensureParameterNotNull(css);
        this.css = css;
    }

    @Override
    public void loadStyleSource() {
        STYLE_SOURCE_LOCK.writeLock().lock();
        try {
            init();
            rules.clear();
            nodeRules.clear();
            wayRules.clear();
            wayNoAreaRules.clear();
            relationRules.clear();
            multipolygonRules.clear();
            canvasRules.clear();
            try (InputStream in = getSourceInputStream()) {
                try {
                    // evaluate @media { ... } blocks
                    MapCSSParser preprocessor = new MapCSSParser(in, "UTF-8", MapCSSParser.LexicalState.PREPROCESSOR);
                    String mapcss = preprocessor.pp_root(this);

                    // do the actual mapcss parsing
                    InputStream in2 = new ByteArrayInputStream(mapcss.getBytes(StandardCharsets.UTF_8));
                    MapCSSParser parser = new MapCSSParser(in2, "UTF-8", MapCSSParser.LexicalState.DEFAULT);
                    parser.sheet(this);

                    loadMeta();
                    loadCanvas();
                    loadSettings();
                } finally {
                    closeSourceInputStream(in);
                }
            } catch (IOException e) {
                Main.warn(tr("Failed to load Mappaint styles from ''{0}''. Exception was: {1}", url, e.toString()));
                Main.error(e);
                logError(e);
            } catch (TokenMgrError e) {
                Main.warn(tr("Failed to parse Mappaint styles from ''{0}''. Error was: {1}", url, e.getMessage()));
                Main.error(e);
                logError(e);
            } catch (ParseException e) {
                Main.warn(tr("Failed to parse Mappaint styles from ''{0}''. Error was: {1}", url, e.getMessage()));
                Main.error(e);
                logError(new ParseException(e.getMessage())); // allow e to be garbage collected, it links to the entire token stream
            }
            // optimization: filter rules for different primitive types
            for (MapCSSRule r: rules) {
                // find the rightmost selector, this must be a GeneralSelector
                Selector selRightmost = r.selector;
                while (selRightmost instanceof ChildOrParentSelector) {
                    selRightmost = ((ChildOrParentSelector) selRightmost).right;
                }
                MapCSSRule optRule = new MapCSSRule(r.selector.optimizedBaseCheck(), r.declaration);
                final String base = ((GeneralSelector) selRightmost).getBase();
                switch (base) {
                    case "node":
                        nodeRules.add(optRule);
                        break;
                    case "way":
                        wayNoAreaRules.add(optRule);
                        wayRules.add(optRule);
                        break;
                    case "area":
                        wayRules.add(optRule);
                        multipolygonRules.add(optRule);
                        break;
                    case "relation":
                        relationRules.add(optRule);
                        multipolygonRules.add(optRule);
                        break;
                    case "*":
                        nodeRules.add(optRule);
                        wayRules.add(optRule);
                        wayNoAreaRules.add(optRule);
                        relationRules.add(optRule);
                        multipolygonRules.add(optRule);
                        break;
                    case "canvas":
                        canvasRules.add(r);
                        break;
                    case "meta":
                    case "setting":
                        break;
                    default:
                        final RuntimeException e = new RuntimeException(MessageFormat.format("Unknown MapCSS base selector {0}", base));
                        Main.warn(tr("Failed to parse Mappaint styles from ''{0}''. Error was: {1}", url, e.getMessage()));
                        Main.error(e);
                        logError(e);
                }
            }
            nodeRules.initIndex();
            wayRules.initIndex();
            wayNoAreaRules.initIndex();
            relationRules.initIndex();
            multipolygonRules.initIndex();
            canvasRules.initIndex();
        } finally {
            STYLE_SOURCE_LOCK.writeLock().unlock();
        }
    }

    @Override
    public InputStream getSourceInputStream() throws IOException {
        if (css != null) {
            return new ByteArrayInputStream(css.getBytes(StandardCharsets.UTF_8));
        }
        CachedFile cf = getCachedFile();
        if (isZip) {
            File file = cf.getFile();
            zipFile = new ZipFile(file, StandardCharsets.UTF_8);
            zipIcons = file;
            ZipEntry zipEntry = zipFile.getEntry(zipEntryPath);
            return zipFile.getInputStream(zipEntry);
        } else {
            zipFile = null;
            zipIcons = null;
            return cf.getInputStream();
        }
    }

    @Override
    public CachedFile getCachedFile() throws IOException {
        return new CachedFile(url).setHttpAccept(MAPCSS_STYLE_MIME_TYPES);
    }

    @Override
    public void closeSourceInputStream(InputStream is) {
        super.closeSourceInputStream(is);
        if (isZip) {
            Utils.close(zipFile);
        }
    }

    /**
     * load meta info from a selector "meta"
     */
    private void loadMeta() {
        Cascade c = constructSpecial("meta");
        String pTitle = c.get("title", null, String.class);
        if (title == null) {
            title = pTitle;
        }
        String pIcon = c.get("icon", null, String.class);
        if (icon == null) {
            icon = pIcon;
        }
    }

    private void loadCanvas() {
        Cascade c = constructSpecial("canvas");
        backgroundColorOverride = c.get("fill-color", null, Color.class);
        if (backgroundColorOverride == null) {
            backgroundColorOverride = c.get("background-color", null, Color.class);
            if (backgroundColorOverride != null) {
                Main.warn(tr("Detected deprecated ''{0}'' in ''{1}'' which will be removed shortly. Use ''{2}'' instead.", "canvas{background-color}", url, "fill-color"));
            }
        }
    }

    private void loadSettings() {
        settings.clear();
        settingValues.clear();
        MultiCascade mc = new MultiCascade();
        Node n = new Node();
        String code = LanguageInfo.getJOSMLocaleCode();
        n.put("lang", code);
        // create a fake environment to read the meta data block
        Environment env = new Environment(n, mc, "default", this);

        for (MapCSSRule r : rules) {
            if ((r.selector instanceof GeneralSelector)) {
                GeneralSelector gs = (GeneralSelector) r.selector;
                if (gs.getBase().equals("setting")) {
                    if (!gs.matchesConditions(env)) {
                        continue;
                    }
                    env.layer = null;
                    env.layer = gs.getSubpart().getId(env);
                    r.execute(env);
                }
            }
        }
        for (Entry<String, Cascade> e : mc.getLayers()) {
            if ("default".equals(e.getKey())) {
                Main.warn("setting requires layer identifier e.g. 'setting::my_setting {...}'");
                continue;
            }
            Cascade c = e.getValue();
            String type = c.get("type", null, String.class);
            StyleSetting set = null;
            if ("boolean".equals(type)) {
                set = BooleanStyleSetting.create(c, this, e.getKey());
            } else {
                Main.warn("Unkown setting type: "+type);
            }
            if (set != null) {
                settings.add(set);
                settingValues.put(e.getKey(), set.getValue());
            }
        }
    }

    private Cascade constructSpecial(String type) {

        MultiCascade mc = new MultiCascade();
        Node n = new Node();
        String code = LanguageInfo.getJOSMLocaleCode();
        n.put("lang", code);
        // create a fake environment to read the meta data block
        Environment env = new Environment(n, mc, "default", this);

        for (MapCSSRule r : rules) {
            if ((r.selector instanceof GeneralSelector)) {
                GeneralSelector gs = (GeneralSelector) r.selector;
                if (gs.getBase().equals(type)) {
                    if (!gs.matchesConditions(env)) {
                        continue;
                    }
                    r.execute(env);
                }
            }
        }
        return mc.getCascade("default");
    }

    @Override
    public Color getBackgroundColorOverride() {
        return backgroundColorOverride;
    }

    public static long tNew = 0;
    public static long tOld = 0;
    public static long rulesGuessed = 0;
    public static long rulesExecuted = 0;
    public static long elementsProcessed = 0;

    @Override
    public void apply(MultiCascade mc, OsmPrimitive osm, double scale, boolean pretendWayIsClosed) {
        Environment env = new Environment(osm, mc, null, this);
        MapCSSRuleIndex matchingRuleIndex;
        if (osm instanceof Node) {
            matchingRuleIndex = nodeRules;
        } else if (osm instanceof Way) {
            if (osm.isKeyFalse("area")) {
                matchingRuleIndex = wayNoAreaRules;
            } else {
                matchingRuleIndex = wayRules;
            }
        } else {
            if (((Relation) osm).isMultipolygon()) {
                matchingRuleIndex = multipolygonRules;
            } else if (osm.hasKey("#canvas")) {
                matchingRuleIndex = canvasRules;
            } else {
                matchingRuleIndex = relationRules;
            }
        }

        // the declaration indices are sorted, so it suffices to save the
        // last used index
        int lastDeclUsed = -1;

        if (false) {
            ArrayList<MapCSSRule> old = new ArrayList<>();
            ArrayList<MapCSSRule> pq = new ArrayList<>();

            // To avoid cache times.
            matchingRuleIndex.getRuleCandidates(osm);
            matchingRuleIndex.getRuleCandidatesOld(osm);

            // ------------ New
            long t1 = System.nanoTime();
            Iterator<MapCSSRule> candidates = matchingRuleIndex.getRuleCandidates(osm);
            while (candidates.hasNext()) {
                pq.add(candidates.next());
            }

            // ------------ Old
            long t2 = System.nanoTime();
            MapCSSRule[] candidatesOld = matchingRuleIndex.getRuleCandidatesOld(osm);
            for(MapCSSRule r : candidatesOld) {
                old.add(r);
            }
            long t3 = System.nanoTime();

            // Now check result
//            if (old.size() != pq.size()) {
//                System.err.println("Warning: Rule count mismatch.");
//            } else {
//                // Check elements.
//                for (MapCSSRule e : old) {
//                    if (!pq.contains(e)) {
//                        System.err.println("Missing in list: " + e);
//                    }
//                }
//            }
            // Check for sorted:
            for (int i = 0; i < pq.size() - 1; i++) {
                if (pq.get(i).compareTo(pq.get(i+1)) > 0) {
                    System.err.println("Warning: Order is wrong.");
                }
            }

            tNew += t2 - t1;
            tOld += t3 - t2;
        }

        Iterator<MapCSSRule> ruleCandidates = matchingRuleIndex.getRuleCandidates(osm);
        while (ruleCandidates.hasNext()) {
            rulesGuessed++;
            MapCSSRule r = ruleCandidates.next();
            env.clearSelectorMatchingInformation();
            env.layer = null;
            String sub = env.layer = r.selector.getSubpart().getId(env);
            if (r.selector.matches(env)) { // as side effect env.parent will be set (if s is a child selector)
                Selector s = r.selector;
                if (s.getRange().contains(scale)) {
                    mc.range = Range.cut(mc.range, s.getRange());
                } else {
                    mc.range = mc.range.reduceAround(scale, s.getRange());
                    continue;
                }

                if (r.declaration.idx == lastDeclUsed) continue; // don't apply one declaration more than once
                lastDeclUsed = r.declaration.idx;
                if ("*".equals(sub)) {
                    for (Entry<String, Cascade> entry : mc.getLayers()) {
                        env.layer = entry.getKey();
                        if ("*".equals(env.layer)) {
                            continue;
                        }
                        r.execute(env);
                    }
                }
                env.layer = sub;
                r.execute(env);
                rulesExecuted++;
            }
        }
        elementsProcessed++;
    }

    public boolean evalSupportsDeclCondition(String feature, Object val) {
        if (feature == null) return false;
        if (SUPPORTED_KEYS.contains(feature)) return true;
        switch (feature) {
            case "user-agent":
            {
                String s = Cascade.convertTo(val, String.class);
                return "josm".equals(s);
            }
            case "min-josm-version":
            {
                Float v = Cascade.convertTo(val, Float.class);
                return v != null && Math.round(v) <= Version.getInstance().getVersion();
            }
            case "max-josm-version":
            {
                Float v = Cascade.convertTo(val, Float.class);
                return v != null && Math.round(v) >= Version.getInstance().getVersion();
            }
            default:
                return false;
        }
    }

    @Override
    public String toString() {
        return Utils.join("\n", rules);
    }
}
