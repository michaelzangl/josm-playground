// License: GPL. For details, see LICENSE file.
package opengltest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.media.opengl.GL2;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.osm.BBox;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.osm.visitor.paint.StyledMapRenderer;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.gui.layer.Layer;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.gui.mappaint.mapcss.Selector;
import org.openstreetmap.josm.tools.CompositeList;

public class OsmStyledLayerRenderer extends StyledMapRenderer implements LayerRenderer {

    public OsmStyledLayerRenderer() {
        super(null, null, false);
    }

    OpenglDrawer drawer = new OpenglDrawer();
    private Bounds oldBounds;

    @Override
    public void render(GL2 gl, Layer layer, Bounds bounds, MapView mv) {
        if (oldBounds == null || !oldBounds.equals(bounds)) {
            drawer.invalidateAll();
        }
        oldBounds = bounds;
        nc = mv;
        drawer.nc = mv;
        getSettings(true);

        long timeStart = System.currentTimeMillis();
        BBox bbox = bounds.toBBox();
        OsmDataLayer osmLayer = (OsmDataLayer) layer;

        List<Node> nodes = osmLayer.data.searchNodes(bbox);
        List<Way> ways = osmLayer.data.searchWays(bbox);
        List<Relation> relations = osmLayer.data.searchRelations(bbox);

        final List<StyleRecord> allStyleElems = new ArrayList<>(nodes.size() + ways.size() + relations.size());

        ConcurrentTasksHelper helper = new ConcurrentTasksHelper(allStyleElems, osmLayer.data);

        helper.process(relations);
        helper.process(new CompositeList<>(nodes, ways));

        Collections.sort(allStyleElems); // TODO: try parallel sort when switching to Java 8

        for (StyleRecord r : allStyleElems) {
            drawer.updatePrimitive(r.osm, r.style, r.flags);
        }
        drawer.primitivesUpdated();

        long timePhase1 = System.currentTimeMillis();
        System.err.print("phase 1 (calculate styles): " + (timePhase1 - timeStart) + " ms");
        drawer.paint(gl);

        long timeFinished = System.currentTimeMillis();
        System.err.println("; phase 2 (draw): " + (timeFinished - timePhase1) + " ms; total: " + (timeFinished - timeStart) + " ms" +
            " (scale: " + getCircum() + " zoom level: " + Selector.GeneralSelector.scale2level(getCircum()) + ")");
    }

}
