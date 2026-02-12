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
                    Thread.sleep(60);
                }
            } catch (Exception e) {}
        });

        server.createContext("/", t -> {
            String html = "<html><body style='background:#000; color:white; text-align:center;'>" +
                          "<h2>Pepper Live</h2><img src='/live' style='max-width:90%; border:4px solid #00844d;' /></body></html>";
            t.sendResponseHeaders(200, html.getBytes().length);
            t.getResponseBody().write(html.getBytes());
            t.getResponseBody().close();
        });

        server.start();
    }

    public void update(BufferedImage img) {
        encoderService.submit(() -> {
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                ImageIO.write(img, "jpg", baos);
                synchronized (lock) { this.currentFrame = baos.toByteArray(); }
            } catch (Exception e) {}
        });
    }
}