// License: GPL. For details, see LICENSE file.
package opengltest;


import java.awt.Color;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.geom.Area;
import java.util.ArrayList;
import java.util.List;

import javax.media.opengl.GL2;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.osm.BBox;
import org.openstreetmap.josm.data.osm.Changeset;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.osm.visitor.Visitor;
import org.openstreetmap.josm.data.osm.visitor.paint.WireframeMapRenderer;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.gui.layer.Layer;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;

public class OsmWireframeLayerRenderer extends WireframeMapRenderer implements LayerRenderer, Visitor {

    private GL2 gl;

    private OpenglDrawer drawer = new OpenglDrawer();

    public OsmWireframeLayerRenderer() {
        super(null, null, true);
    }

    @Override
    public void render(GL2 gl, Layer layer, Bounds box, MapView mv) {
        this.gl = gl;
        this.nc = mv;
        OsmDataLayer osmLayer = (OsmDataLayer) layer;
        renderDownloadArea(gl, osmLayer, box, mv);

        BBox bbox = box.toBBox();

        for (final Relation rel : osmLayer.data.searchRelations(bbox)) {
            if (rel.isDrawable() && !osmLayer.data.isSelected(rel) && !rel.isDisabledAndHidden()) {
                rel.accept(this);
            }
        }

        // draw tagged ways first, then untagged ways, then highlighted ways
        List<Way> highlightedWays = new ArrayList<>();
        List<Way> untaggedWays = new ArrayList<>();

        for (final Way way : osmLayer.data.searchWays(bbox)){
            if (way.isDrawable() && !osmLayer.data.isSelected(way) && !way.isDisabledAndHidden()) {
                if (way.isHighlighted()) {
                    highlightedWays.add(way);
                } else if (!way.isTagged()) {
                    untaggedWays.add(way);
                } else {
                    way.accept(this);
                }
            }
        }

        // Display highlighted ways after the other ones (fix #8276)
        List<Way> specialWays = new ArrayList<>(untaggedWays);
        specialWays.addAll(highlightedWays);
        for (final Way way : specialWays){
            way.accept(this);
        }
        specialWays.clear();

        for (final OsmPrimitive osm : osmLayer.data.getSelected()) {
            if (osm.isDrawable()) {
                osm.accept(this);
            }
        }

        for (final OsmPrimitive osm: osmLayer.data.searchNodes(bbox)) {
            if (osm.isDrawable() && !osmLayer.data.isSelected(osm) && !osm.isDisabledAndHidden())
            {
                osm.accept(this);
            }
        }
//        drawVirtualNodes(osmLayer.data, bbox);

        // draw highlighted way segments over the already drawn ways. Otherwise each
        // way would have to be checked if it contains a way segment to highlight when
        // in most of the cases there won't be more than one segment. Since the wireframe
        // renderer does not feature any transparency there should be no visual difference.
//        for (final WaySegment wseg : osmLayer.data.getHighlightedWaySegments()) {
//            drawSegment(nc.getPoint(wseg.getFirstNode()), nc.getPoint(wseg.getSecondNode()), highlightColor, false);
//        }
    }

    private void renderDownloadArea(GL2 gl, OsmDataLayer osmLayer, Bounds box, MapView mv) {
        // initialize area with current viewport
        Rectangle b = mv.getBounds();
        // on some platforms viewport bounds seem to be offset from the left,
        // over-grow it just to be sure
        b.grow(100, 100);
        Area a = new Area(b);

        // now successively subtract downloaded areas
        for (Bounds bounds : osmLayer.data.getDataSourceBounds()) {
            if (bounds.isCollapsed()) {
                continue;
            }
            Point p1 = mv.getPoint(bounds.getMin());
            Point p2 = mv.getPoint(bounds.getMax());


            Rectangle r = new Rectangle(Math.min(p1.x, p2.x), Math.min(p1.y, p2.y), Math.abs(p2.x - p1.x),
                    Math.abs(p2.y - p1.y));
            a.subtract(new Area(r));
        }

        // paint remainder
        //g.setPaint(hatched);
        //g.fill(a);
    }

    @Override
    public void visit(Node n) {
        if (n.isIncomplete()) return;

        if (n.isHighlighted()) {
            drawNode(n, highlightColor, selectedNodeSize, fillSelectedNode);
        } else {
            Color color;

            if (n.isDisabled()) {
                color = inactiveColor;
            } else if (n.isSelected()) {
                color = selectedColor;
            } else if (n.isMemberOfSelected()) {
                color = relationSelectedColor;
            } else if (n.isConnectionNode()) {
                if (isNodeTagged(n)) {
                    color = taggedConnectionColor;
                } else {
                    color = connectionColor;
                }
            } else {
                if (isNodeTagged(n)) {
                    color = taggedColor;
                } else {
                    color = nodeColor;
                }
            }

            final int size = max((ds.isSelected(n) ? selectedNodeSize : 0),
                    (isNodeTagged(n) ? taggedNodeSize : 0),
                    (n.isConnectionNode() ? connectionNodeSize : 0),
                    unselectedNodeSize);

            final boolean fill = (ds.isSelected(n) && fillSelectedNode) ||
            (isNodeTagged(n) && fillTaggedNode) ||
            (n.isConnectionNode() && fillConnectionNode) ||
            fillUnselectedNode;

            drawNode(n, color, size, fill);
        }
    }

    @Override
    public void drawNode(Node n, Color color, int size, boolean fill) {
        if (size > 1) {
            Point p = nc.getPoint(n);
            int radius = (size + 1) / 2;

            if ((p.x < 0) || (p.y < 0) || (p.x > nc.getWidth())
                    || (p.y > nc.getHeight()))
                return;

            if (fill) {
            } else {
            }
        }

    }

    @Override
    public void visit(Way w) {
        // TODO Auto-generated method stub
    }

    @Override
    public void visit(Relation r) {
        // TODO Auto-generated method stub

    }

    @Override
    public void visit(Changeset cs) {
        // TODO Auto-generated method stub

    }

}
