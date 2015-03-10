// License: GPL. For details, see LICENSE file.
package opengltest;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Hashtable;
import java.util.LinkedList;

import javax.media.opengl.GL2;

import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.gui.NavigatableComponent;
import org.openstreetmap.josm.gui.mappaint.ElemStyle;

public class OpenglDrawer {

    private static final int FLAG_NORMAL = 0;
    static final int FLAG_DISABLED = 1;
    private static final int FLAG_MEMBER_OF_SELECTED = 2;
    private static final int FLAG_SELECTED = 4;
    private static final int FLAG_OUTERMEMBER_OF_SELECTED = 8;

    protected NavigatableComponent nc;

    @Deprecated
    private class LineDrawer {
        private class LinePoint {
            // Line center
            double x;
            double y;
            LinePoint opposite;
            // Normal
            double nx;
            double ny;
            float width;
            // blur
            float blur;

            public LinePoint(double x, double y, double nx, double ny, float width, float blur) {
                super();
                this.x = x;
                this.y = y;
                this.nx = nx;
                this.ny = ny;
                this.width = width;
                this.blur = blur;
            }
        }

        private void draw(Point[] points, float width, float blur, boolean closed) {
            if (points.length == 0) {
                return;
            }
            double[] pointNormals = new double[points.length * 2];
            if (points.length == 1) {
                pointNormals[0] = 0;
                pointNormals[1] = 1;
            } else {
                for (int i = 0; i < points.length - 1; i++) {
                    double d1 = (points[i].x - points[i + 1].x);
                    double d2 = (points[i].y - points[i + 1].y);
                    double s = Math.hypot(d1, d2);
                    pointNormals[i * 2] = d1 / s;
                    pointNormals[i * 2 + 1] = d2 / s;
                }
                pointNormals[points.length * 2 - 2] = points.length * 2 - 4;
                pointNormals[points.length * 2 - 1] = points.length * 2 - 3;
            }
            LinkedList<LinePoint> lps = new LinkedList<>();
            for (int i = 0; i < points.length; i++) {
                LinePoint p1 = new LinePoint(points[i].x, points[i].y, pointNormals[i * 2], pointNormals[i * 2 + 1],
                        width, blur);
                LinePoint p2 = new LinePoint(points[i].x, points[i].y, pointNormals[i * 2], pointNormals[i * 2 + 1],
                        width, blur);
                p1.opposite = p2;
                p2.opposite = p1;
                lps.add(0, p1);
                lps.add(p2);
            }

        }
    }

    ArrayList<OsmDataVBO> allVBOs = new ArrayList<>();
    // Index: Z-index, texture
    private Hashtable<StyleType, OsmDataVBO> currentVBO = new Hashtable<>();

    private Hashtable<OsmPrimitive, ArrayList<OsmDataVBO>> usedVBOs = new Hashtable<>();

    private class PrimitiveAndData {
        OsmPrimitive p;
        OsmDataVBO o;
        public PrimitiveAndData(OsmPrimitive p, OsmDataVBO o) {
            super();
            this.p = p;
            this.o = o;
        }
    }

    private ArrayList<PrimitiveAndData> newlyUsedVBOs = new ArrayList<>();



    public void paint(GL2 gl) {

        Collections.sort(allVBOs);

        for (OsmDataVBO v : allVBOs) {
            v.draw(nc, gl);
        }
        invalidateAll();
    }

    public void invalidateAll() {
        // invalidate all:
        allVBOs.clear();
        currentVBO.clear();
        usedVBOs.clear();
    }

    public void updatePrimitive(OsmPrimitive osm, ElemStyle style, int flags) {
        // ... TODO
        if (usedVBOs.containsKey(osm)) {
            System.out.println("Try to add twice: " + osm);
            return;
        }

        StyleType record = new StyleType(style, osm, flags);
        OsmDataVBO oldVBO = currentVBO.get(record);
        OsmDataVBO vbo = oldVBO;
        if (vbo == null || vbo.isClosed()) {
            vbo = new OsmDataVBO(record);
        }

        if (!vbo.addWithStyle(nc, osm, style)) {
            vbo = new OsmDataVBO(record);
            if (!vbo.addWithStyle(nc, osm, style)) {
                System.err.println("Could not draw: " + osm);
            }
        }

        newlyUsedVBOs.add(new PrimitiveAndData(osm, vbo));

        if (vbo != oldVBO) {
            allVBOs.add(vbo);
            currentVBO.put(record, vbo);
        }
    }

    public void primitivesUpdated() {
        for (PrimitiveAndData n: newlyUsedVBOs) {
            if (usedVBOs.containsKey(n.p)) {
                usedVBOs.get(n.p).add(n.o);
            } else {
                ArrayList<OsmDataVBO> l = new ArrayList<>();
                l.add(n.o);
                usedVBOs.put(n.p, l);
            }
        }
        newlyUsedVBOs.clear();
    }
}
