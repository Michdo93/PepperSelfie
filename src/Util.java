import javax.imageio.ImageIO;
import javax.print.*;
import javax.print.attribute.HashPrintRequestAttributeSet;
import javax.print.attribute.PrintRequestAttributeSet;
import javax.print.attribute.standard.Copies;
import javax.print.attribute.standard.MediaPrintableArea;
import javax.print.attribute.standard.OrientationRequested;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.List;
import org.openimaj.image.ImageUtilities;
import org.openimaj.image.MBFImage;
import org.openimaj.image.colour.RGBColour;
import org.openimaj.image.typography.general.GeneralFont;

public class Util {
    private static final int V_GA_WIDTH = 640;
    private static final int V_GA_HEIGHT = 480;
    private static final int HD_WIDTH = 1280;
    private static final int HD_HEIGHT = 960;
    private static final Float[] HFU_GREEN = RGBColour.RGB(0.0F, 0.5176F, 0.3020F);

    // Cache für das Logo
    private static MBFImage cachedLogo = null;

    static {
        try (InputStream is = Util.class.getResourceAsStream("/resources/Logo_2.png")) {
            if (is != null) {
                cachedLogo = ImageUtilities.createMBFImage(ImageIO.read(is), true);
            }
        } catch (IOException e) {
            System.err.println("Fehler beim Laden des Logos: " + e.getMessage());
        }
    }

    public static BufferedImage toBufferedImage(List<Object> remoteImageData) {
        return convertRawToBuffer(remoteImageData, V_GA_WIDTH, V_GA_HEIGHT);
    }

    public static BufferedImage toBufferedImage_picture(List<Object> remoteImageData) {
        BufferedImage img = convertRawToBuffer(remoteImageData, HD_WIDTH, HD_HEIGHT);
        return addBranding(img);
    }

    private static BufferedImage convertRawToBuffer(List<Object> remoteData, int w, int h) {
        ByteBuffer buffer = (ByteBuffer) remoteData.get(6);
        byte[] data = buffer.array();

        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] pixels = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();

        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = (0xFF << 24) | 
                       ((data[i * 3] & 0xFF) << 16) | 
                       ((data[i * 3 + 1] & 0xFF) << 8) | 
                       (data[i * 3 + 2] & 0xFF);
        }
        return img;
    }

    private static BufferedImage addBranding(BufferedImage img) {
        MBFImage mbfImage = ImageUtilities.createMBFImage(img, false);
        
        if (cachedLogo != null) {
            mbfImage.overlayInplace(cachedLogo, 1, 20);
        }

        GeneralFont font = new GeneralFont("Arial Narrow", 50);
        if (PepperSelfie.config != null) {
            mbfImage.drawText(PepperSelfie.config.imageText(), 
                              PepperSelfie.config.positionX(), 70, font, 50, HFU_GREEN);
            mbfImage.drawText(PepperSelfie.config.imageDate(), 
                              500, 120, font, 50, HFU_GREEN);
        }
        return ImageUtilities.createBufferedImage(mbfImage);
    }

    /**
     * Druckt das Bild, indem es den installierten Drucker anhand der IP-Adresse sucht.
     */
    public static void printImage(BufferedImage image, int width, int height, int copies, String printerIP) {
        try {
            // 1. Alle verfügbaren Drucker auflisten (für Debugging)
            PrintService[] services = PrintServiceLookup.lookupPrintServices(null, null);
            PrintService selectedService = null;

            System.out.println("--- Verfügbare Drucker im System ---");
            for (PrintService service : services) {
                String serviceName = service.getName();
                System.out.println("- " + serviceName);
                
                // Falls die IP im Namen vorkommt, wählen wir diesen Drucker
                if (serviceName.contains(printerIP)) {
                    selectedService = service;
                }
            }
            System.out.println("------------------------------------");

            if (selectedService == null) {
                System.err.println("FEHLER: Kein Drucker gefunden, der die IP '" + printerIP + "' im Namen trägt.");
                return;
            }

            System.out.println("Gewählter Drucker: " + selectedService.getName());

            // 2. Bild für den Druck vorbereiten
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "jpg", baos);
            byte[] byteImage = baos.toByteArray();

            // 3. Druckjob erstellen
            DocPrintJob job = selectedService.createPrintJob();
            Doc doc = new SimpleDoc(byteImage, DocFlavor.BYTE_ARRAY.JPEG, null);

            PrintRequestAttributeSet attrs = new HashPrintRequestAttributeSet();
            attrs.add(new Copies(copies));
            attrs.add(new MediaPrintableArea(0, 0, width, height, MediaPrintableArea.MM));
            attrs.add(OrientationRequested.LANDSCAPE);

            System.out.println("Sende Daten an Drucker...");
            job.print(doc, attrs);
            System.out.println("Druckauftrag erfolgreich übermittelt.");

        } catch (Exception e) {
            System.err.println("Kritischer Fehler beim Druckvorgang:");
            e.printStackTrace();
        }
    }
}