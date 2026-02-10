import java.awt.image.BufferedImage;
import javax.swing.JFrame;
import org.openimaj.image.DisplayUtilities;

public class ImagePlayer {
	private String windowName;
	private BufferedImage image;
	private JFrame frame;

	public ImagePlayer(String windowName, String caption) {
		this.frame = DisplayUtilities.createNamedWindow(windowName, caption, true);
	}

	public void update(BufferedImage image) {
		this.image = image;
		DisplayUtilities.displayName(image, this.windowName);
	}

	public BufferedImage getCurrentImage() {
		return this.image;
	}

	public void dispose() {
		this.frame.dispose();
	}
}
