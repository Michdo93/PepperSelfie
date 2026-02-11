import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.InetSocketAddress;
import java.util.concurrent.*;
import javax.imageio.ImageIO;

public class WebStreamer {
    private byte[] currentFrame;
    private final Object lock = new Object();
    private final ExecutorService encoderService = Executors.newSingleThreadExecutor();

    public WebStreamer() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(9090), 0);
        
        server.createContext("/live", t -> {
            t.getResponseHeaders().add("Content-Type", "multipart/x-mixed-replace; boundary=frame");
            t.sendResponseHeaders(200, 0);
            OutputStream os = t.getResponseBody();
            try {
                while (true) {
                    byte[] frame;
                    synchronized (lock) { frame = currentFrame; }
                    
                    if (frame != null) {
                        os.write(("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: " + frame.length + "\r\n\r\n").getBytes());
                        os.write(frame);
                        os.write(("\r\n").getBytes());
                        os.flush();
                    }
                    Thread.sleep(50); // ca. 20 FPS im Browser möglich
                }
            } catch (Exception e) { /* Verbindung geschlossen */ }
        });

        server.createContext("/", t -> {
            String html = "<html><head><title>Pepper Selfie</title></head>" +
                          "<body style='background:#111; color:white; font-family:sans-serif; text-align:center;'>" +
                          "<h1>Pepper Live-Vorschau</h1>" +
                          "<div style='border:5px solid #00844d; display:inline-block;'>" +
                          "<img src='/live' style='width:100%; max-width:1280px;' />" +
                          "</div></body></html>";
            t.sendResponseHeaders(200, html.getBytes().length);
            t.getResponseBody().write(html.getBytes());
            t.getResponseBody().close();
        });

        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.println("Web-Streamer läuft auf Port 9090");
    }

    public void update(BufferedImage img) {
        if (img == null) return;
        encoderService.submit(() -> {
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                ImageIO.write(img, "jpg", baos);
                synchronized (lock) {
                    this.currentFrame = baos.toByteArray();
                }
            } catch (Exception e) {}
        });
    }
}
