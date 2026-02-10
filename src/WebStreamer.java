import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import javax.imageio.ImageIO;

public class WebStreamer {
    private byte[] currentFrame;
    private final Object lock = new Object();

    public WebStreamer() throws Exception {
        // Port auf 9559 geändert
        int port = 9559; 
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        
        // Stream-Endpunkt
        server.createContext("/live", t -> {
            t.getResponseHeaders().add("Content-Type", "multipart/x-mixed-replace; boundary=frame");
            t.sendResponseHeaders(200, 0);
            OutputStream os = t.getResponseBody();

            try {
                while (true) {
                    byte[] frame;
                    synchronized (lock) { frame = currentFrame; }

                    if (frame != null) {
                        os.write(("--frame\r\nContent-Type: image/jpeg\r\n\r\n").getBytes());
                        os.write(frame);
                        os.write(("\r\n").getBytes());
                        os.flush();
                    }
                    Thread.sleep(66); 
                }
            } catch (Exception e) {
                // Client hat die Verbindung getrennt
            }
        });

        // Hauptseite
        server.createContext("/", t -> {
            String html = "<html><head><title>Pepper Selfie</title></head>" +
                          "<body style='background:#111; color:white; font-family:sans-serif; text-align:center;'>" +
                          "<h1>Pepper Selfie</h1>" +
                          "<div style='margin-bottom:20px;'>Stream-Port: 9559</div>" +
                          "<img src='/live' style='border:5px solid #00844d; max-width:90%; height:auto;'>" +
                          "<p>Status: Live-Übertragung aktiv</p>" +
                          "</body></html>";
            t.sendResponseHeaders(200, html.getBytes().length);
            t.getResponseBody().write(html.getBytes());
            t.getResponseBody().close();
        });

        // Executor auf null setzen (Default) oder einen kleinen Pool nutzen
        server.setExecutor(null); 
        server.start();
        System.out.println("Web-Interface erreichbar unter: http://[SERVER-IP]:9559");
    }

    public void update(BufferedImage img) {
        if (img == null) return;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            // Kompression auf 0.7 oder 0.8 setzen, falls das WLAN hakt
            ImageIO.write(img, "jpg", baos);
            synchronized (lock) {
                this.currentFrame = baos.toByteArray();
            }
        } catch (Exception e) {
            // Frame überspringen
        }
    }
}