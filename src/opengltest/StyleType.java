// License: GPL. For details, see LICENSE file.
package opengltest;


import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.gui.mappaint.ElemStyle;
import org.openstreetmap.josm.gui.mappaint.NodeElemStyle;

class StyleType implements Comparable<StyleType> {
    float major_z_index;
    float z_index;
    float object_z_index;
    boolean isNodeElmstyle;

    final int flags;
    // Note: Do we need an int here?
    private int textureId;

    public StyleType(ElemStyle style, OsmPrimitive osm, int flags) {
        this.flags = flags;
        this.major_z_index = style.major_z_index;
        this.z_index = style.z_index;
        this.object_z_index = style.object_z_index;
        this.isNodeElmstyle = style == NodeElemStyle.SIMPLE_NODE_ELEMSTYLE;
    }

    @Override
    public int compareTo(StyleType other) {
        if ((this.flags & OpenglDrawer.FLAG_DISABLED) != 0 && (other.flags & OpenglDrawer.FLAG_DISABLED) == 0)
            return -1;
        if ((this.flags & OpenglDrawer.FLAG_DISABLED) == 0 && (other.flags & OpenglDrawer.FLAG_DISABLED) != 0)
            return 1;

        int d0 = Float.compare(this.major_z_index, other.major_z_index);
        if (d0 != 0)
            return d0;

        // selected on top of member of selected on top of unselected
        // FLAG_DISABLED bit is the same at this point
        if (this.flags > other.flags)
            return 1;
        if (this.flags < other.flags)
            return -1;

        int dz = Float.compare(this.z_index, other.z_index);
        if (dz != 0)
            return dz;

        // simple node on top of icons and shapes
        if (this.isNodeElmstyle && !other.isNodeElmstyle)
            return 1;
        if (!this.isNodeElmstyle && other.isNodeElmstyle)
            return -1;

        return Float.compare(this.object_z_index, other.object_z_index);
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + flags;
        result = prime * result + (isNodeElmstyle ? 1231 : 1237);
        result = prime * result + Float.floatToIntBits(major_z_index);
        result = prime * result + Float.floatToIntBits(object_z_index);
        result = prime * result + textureId;
        result = prime * result + Float.floatToIntBits(z_index);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        StyleType other = (StyleType) obj;
        if (flags != other.flags)
            return false;
        if (isNodeElmstyle != other.isNodeElmstyle)
            return false;
        if (Float.floatToIntBits(major_z_index) != Float.floatToIntBits(other.major_z_index))
            return false;
        if (Float.floatToIntBits(object_z_index) != Float.floatToIntBits(other.object_z_index))
            return false;
        if (textureId != other.textureId)
            return false;
        if (Float.floatToIntBits(z_index) != Float.floatToIntBits(other.z_index))
            return false;
        return true;
    }
}