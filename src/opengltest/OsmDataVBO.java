// License: GPL. For details, see LICENSE file.
package opengltest;

import java.awt.Color;
import java.awt.Point;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Iterator;

import javax.media.opengl.GL;
import javax.media.opengl.GL2;

import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.gui.NavigatableComponent;
import org.openstreetmap.josm.gui.mappaint.AreaElemStyle;
import org.openstreetmap.josm.gui.mappaint.ElemStyle;
import org.openstreetmap.josm.gui.mappaint.LineElemStyle;

/**
 * Buffer data format: x(f), y(f), z, u, v, r(8),g(8),b(8),a(8)
 * @author michael
 *
 */
public class OsmDataVBO implements Comparable<OsmDataVBO> {
    /**
     * Bytes we need for one vertex
     */
    private static final int VERTEX_LENGTH = 5 * 4 + 4;
    private static final int TRIAMGLE_LENGTH = 3 * VERTEX_LENGTH;
    private static final int BUFFER_TRIANGLES = 1024 * 200;

    private class BufferFullException extends Exception {}

    private ArrayList<OsmPrimitive> contained = new ArrayList<>();
    private StyleType styleType;
    private boolean closed;

    private static final float Z = 0;

    /**
     * The last texture we set.
     */
    private int currentTexture = 0;

    private int currentTriangles = 0;

    protected ByteBuffer byteBuffer;

    public OsmDataVBO(StyleType styleType) {
        this.styleType = styleType;
        byteBuffer = ByteBuffer.allocateDirect(BUFFER_TRIANGLES * TRIAMGLE_LENGTH);
        byteBuffer.order(ByteOrder.nativeOrder());
    }

    @Override
    public int compareTo(OsmDataVBO other) {
        return styleType.compareTo(other.styleType);
    }

    public void setClosed(boolean closed) {
        this.closed = closed;
    }

    public boolean isClosed() {
        return closed;
    }

    public void invalidate() {
        byteBuffer.rewind();
        currentTriangles = 0;
    }

    protected void addImage(float x1, float y1, float x2, float y2, float u1, float v1, float u2, float v2,
            int activeColor) throws BufferFullException {
        if (currentTriangles > BUFFER_TRIANGLES - 2) {
            throw new BufferFullException();
        }
        byteBuffer.position(currentTriangles * TRIAMGLE_LENGTH);
        addPointPrimitive(x1, y1, u1, v1, activeColor);
        addPointPrimitive(x1, y2, u1, v2, activeColor);
        addPointPrimitive(x2, y1, u2, v1, activeColor);
        addPointPrimitive(x2, y1, u2, v1, activeColor);
        addPointPrimitive(x1, y2, u1, v2, activeColor);
        addPointPrimitive(x2, y2, u2, v2, activeColor);
        currentTriangles += 2;
    }

    public void addTriangle(float x1, float y1, float x2, float y2, float x3, float y3, float u1, float v1, float u2,
            float v2, float u3, float v3, int activeColor1, int activeColor2, int activeColor3) throws BufferFullException {
        if (currentTriangles > BUFFER_TRIANGLES - 1) {
            throw new BufferFullException();
        }
        byteBuffer.position(currentTriangles * TRIAMGLE_LENGTH);
        addPointPrimitive(x1, y1, u1, v1, activeColor1);
        addPointPrimitive(x2, y2, u2, v2, activeColor2);
        addPointPrimitive(x3, y3, u3, v3, activeColor3);
        currentTriangles += 1;
    }

    private void addPointPrimitive(float x1, float y1, float u, float v, int activeColor) {
        byteBuffer.putFloat(x1);
        byteBuffer.putFloat(y1);
        byteBuffer.putFloat(Z);
        byteBuffer.putFloat(u);
        byteBuffer.putFloat(v);
        byteBuffer.putInt(activeColor);
    }

    public void draw(NavigatableComponent nc, GL2 gl) {
        // System.out.println("draw " + currentTriangles + " tris of " + currentTexture);
        gl.glEnableClientState(GL2.GL_VERTEX_ARRAY);
        gl.glEnableClientState(GL2.GL_TEXTURE_COORD_ARRAY);

        gl.glAlphaFunc(GL2.GL_GREATER, 0.1f);
        gl.glEnable(GL2.GL_ALPHA_TEST);
        gl.glDepthFunc(GL2.GL_LEQUAL);
        gl.glEnable(GL2.GL_DEPTH_TEST);

        gl.glEnable(GL2.GL_TEXTURE_2D);

        byteBuffer.rewind();
        gl.glBindTexture(GL.GL_TEXTURE_2D, currentTexture);

        gl.glVertexPointer(3, GL2.GL_FLOAT, 6 * 4, byteBuffer);
        byteBuffer.position(3 * 4);
        gl.glTexCoordPointer(2, GL2.GL_FLOAT, 6 * 4, byteBuffer);
        byteBuffer.position(5 * 4);
        gl.glColorPointer(4, GL2.GL_UNSIGNED_BYTE, 6 * 4, byteBuffer);

        gl.glEnableClientState(GL2.GL_COLOR_ARRAY);
        gl.glDrawArrays(GL2.GL_TRIANGLES, 0, currentTriangles * 3);
        gl.glDisableClientState(GL2.GL_COLOR_ARRAY);
    }

    // Add a line segment (6 triangles).
    private void addLineSegment(Point start, Point end, Color color, float lineWidth, float blurWidth)
            throws BufferFullException {
        float x1 = start.x;
        float y1 = start.y;
        float x2 = end.x;
        float y2 = end.y;

        float nx = x1 - x2;
        float ny = y1 - y2;

        float ns = (float) Math.hypot(nx, ny);
        nx /= ns;
        ny /= ns;
        ny *= -1;

        float nx1 = nx * lineWidth / 2;
        float ny1 = ny * lineWidth / 2;

        float nx2 = nx1 + nx * blurWidth;
        float ny2 = ny1 + ny * blurWidth;

        int activeColor = 0;
        // abgr
        activeColor = (0xff << 24) | (color.getRed() << 0) | (color.getGreen() << 8) | (color.getBlue() << 16);
        addTriangle(x1 + ny1, y1 + nx1, x1 - ny1, y1 - nx1, x2 + ny1, y2 + nx1, 0, 0, 0, 0, 0, 0, activeColor, activeColor, activeColor);
        addTriangle(x2 + ny1, y2 + nx1, x2 - ny1, y2 - nx1, x1 - ny1, y1 - nx1, 0, 0, 0, 0, 0, 0, activeColor, activeColor, activeColor);

        // Blur
        addTriangle(x1 + ny2, y1 + nx2, x1 + ny1, y1 + nx1, x2 + ny2, y2 + nx2, 0, 0, 0, 0, 0, 0, activeColor & 0xffffff, activeColor, activeColor & 0xffffff);
        addTriangle(x2 + ny2, y2 + nx2, x2 + ny1, y2 + nx1, x1 + ny1, y1 + nx1, 0, 0, 0, 0, 0, 0, activeColor & 0xffffff, activeColor, activeColor);

        // More blur
        addTriangle(x1 - ny1, y1 - nx1, x1 - ny2, y1 - nx2, x2 - ny1, y2 - nx1, 0, 0, 0, 0, 0, 0, activeColor, activeColor & 0xffffff, activeColor);
        addTriangle(x2 - ny1, y2 - nx1, x2 - ny2, y2 - nx2, x1 - ny2, y1 - nx2, 0, 0, 0, 0, 0, 0, activeColor, activeColor & 0xffffff, activeColor & 0xffffff);
   }

    public boolean addWithStyle(NavigatableComponent nc, OsmPrimitive osm, ElemStyle style) {
        int oldTris = currentTriangles;
        try {
            if (style instanceof LineElemStyle) {
                Point lastPoint = null;
                Iterator<Point> it = new OffsetIterator(nc, ((Way) osm).getNodes(), 0);
                while (it.hasNext()) {
                    Point p = it.next();
                    if (lastPoint != null) {
                        Point p1 = lastPoint;
                        Point p2 = p;
                        addLineSegment(p1, p2, ((LineElemStyle) style).color,
                                ((LineElemStyle) style).line.getLineWidth(), .7f);
                    }
                    lastPoint = p;
                }
            } else if (style instanceof AreaElemStyle) {
            }
        } catch (BufferFullException e) {
            //rollback
            currentTriangles = oldTris;
            return false;
        }
        return true;
    }
}
