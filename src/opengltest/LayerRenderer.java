// License: GPL. For details, see LICENSE file.
package opengltest;

import javax.media.opengl.GL2;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.gui.layer.Layer;

public interface LayerRenderer {
    public void render(GL2 gl, Layer layer, Bounds box, MapView mv);
}
