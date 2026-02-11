import org.openimaj.image.ImageUtilities;
import org.openimaj.image.MBFImage;
import org.openimaj.image.colour.RGBColour;
import org.openimaj.image.typography.general.GeneralFont;

import javax.imageio.ImageIO;
import javax.print.*;
import javax.print.attribute.*;
import javax.print.attribute.standard.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import org.cfg4j.provider.ConfigurationProvider;
import org.cfg4j.provider.ConfigurationProviderBuilder;
import org.cfg4j.source.files.FilesConfigurationSource;

public class Util {

    public static PepperSelfie.PepperSelfieConfig loadConfiguration() {
        Path configPath = Paths.get(System.getProperty("user.dir"), "PepperSelfie.yaml");
        ConfigurationProvider provider = new ConfigurationProviderBuilder()
            .withConfigurationSource(new FilesConfigurationSource(() -> Collections.singletonList(configPath)))
            .build();
        return provider.bind("", PepperSelfie.PepperSelfieConfig.class);
    }

    public static BufferedImage applyBranding(BufferedImage liveImg) {
        // Umwandlung in OpenIMAJ Format für einfaches Branding
        MBFImage mbfImage = ImageUtilities.createMBFImage(liveImg, false);
        
        try {
            // Logo laden
            InputStream is = Util.class.getResourceAsStream("/resources/Logo_2.png");
            if (is != null) {
                MBFImage logo = ImageUtilities.createMBFImage(ImageIO.read(is), true);
                mbfImage.overlayInplace(logo, 1, 20);
            }
        } catch (Exception e) {
            System.err.println("Logo konnte nicht geladen werden.");
        }

        // Text zeichnen
        GeneralFont font = new GeneralFont("Arial Narrow", 50);
        Float[] HFU_GREEN = RGBColour.RGB(0.0F, 0.5176F, 0.3020F);
        
        if (PepperSelfie.config != null) {
            mbfImage.drawText(PepperSelfie.config.imageText(), 
                              PepperSelfie.config.positionX(), 70, font, 50, HFU_GREEN);
            mbfImage.drawText(PepperSelfie.config.imageDate(), 
                              500, 120, font, 40, HFU_GREEN);
        }

        return ImageUtilities.createBufferedImage(mbfImage);
    }

    public static void printImage(BufferedImage image, int width, int height, int copies, String printerIP) {
        try {
            PrintService[] services = PrintServiceLookup.lookupPrintServices(null, null);
            PrintService selectedService = null;

            for (PrintService service : services) {
                if (service.getName().contains(printerIP)) {
                    selectedService = service;
                    break;
                }
            }

            if (selectedService == null) return;

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "jpg", baos);
            byte[] byteImage = baos.toByteArray();

            DocPrintJob job = selectedService.createPrintJob();
            Doc doc = new SimpleDoc(byteImage, DocFlavor.BYTE_ARRAY.JPEG, null);

            PrintRequestAttributeSet attrs = new HashPrintRequestAttributeSet();
            attrs.add(new Copies(copies));
            attrs.add(new MediaPrintableArea(0, 0, width, height, MediaPrintableArea.MM));
            attrs.add(OrientationRequested.LANDSCAPE);

            job.print(doc, attrs);
        } catch (Exception e) { e.printStackTrace(); }
    }
}
