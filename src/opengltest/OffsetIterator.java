// License: GPL. For details, see LICENSE file.
package opengltest;

import java.awt.Point;
import java.util.Iterator;
import java.util.List;

import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.gui.NavigatableComponent;

/**
 * Iterates over a list of Way Nodes and returns screen coordinates that
 * represent a line that is shifted by a certain offset perpendicular
 * to the way direction.
 *
 * There is no intention, to handle consecutive duplicate Nodes in a
 * perfect way, but it is should not throw an exception.
 */
class OffsetIterator implements Iterator<Point> {

    private NavigatableComponent nc;
    private List<Node> nodes;
    private float offset;
    private int idx;

    private Point prev = null;
    /* 'prev0' is a point that has distance 'offset' from 'prev' and the
     * line from 'prev' to 'prev0' is perpendicular to the way segment from
     * 'prev' to the next point.
     */
    private int x_prev0, y_prev0;

    public OffsetIterator(NavigatableComponent nc, List<Node> nodes, float offset) {
        this.nc = nc;
        this.nodes = nodes;
        this.offset = offset;
        idx = 0;
    }

    @Override
    public boolean hasNext() {
        return idx < nodes.size();
    }

    @Override
    public Point next() {
        if (Math.abs(offset) < 0.1f)
            return nc.getPoint(nodes.get(idx++));

        Point current = nc.getPoint(nodes.get(idx));

        if (idx == nodes.size() - 1) {
            ++idx;
            if (prev != null) {
                return new Point(x_prev0 + current.x - prev.x, y_prev0 + current.y - prev.y);
            } else {
                return current;
            }
        }

        Point next = nc.getPoint(nodes.get(idx + 1));

        int dx_next = next.x - current.x;
        int dy_next = next.y - current.y;
        double len_next = Math.sqrt(dx_next * dx_next + dy_next * dy_next);

        if (len_next == 0) {
            len_next = 1; // value does not matter, because dy_next and dx_next is 0
        }

        int x_current0 = current.x + (int) Math.round(offset * dy_next / len_next);
        int y_current0 = current.y - (int) Math.round(offset * dx_next / len_next);

        if (idx == 0) {
            ++idx;
            prev = current;
            x_prev0 = x_current0;
            y_prev0 = y_current0;
            return new Point(x_current0, y_current0);
        } else {
            int dx_prev = current.x - prev.x;
            int dy_prev = current.y - prev.y;

            // determine intersection of the lines parallel to the two
            // segments
            int det = dx_next * dy_prev - dx_prev * dy_next;

            if (det == 0) {
                ++idx;
                prev = current;
                x_prev0 = x_current0;
                y_prev0 = y_current0;
                return new Point(x_current0, y_current0);
            }

            int m = dx_next * (y_current0 - y_prev0) - dy_next * (x_current0 - x_prev0);

            int cx_ = x_prev0 + Math.round((float) m * dx_prev / det);
            int cy_ = y_prev0 + Math.round((float) m * dy_prev / det);
            ++idx;
            prev = current;
            x_prev0 = x_current0;
            y_prev0 = y_current0;
            return new Point(cx_, cy_);
        }
    }

    @Override
    public void remove() {
        throw new UnsupportedOperationException();
    }
}