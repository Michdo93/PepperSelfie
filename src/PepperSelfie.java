import com.aldebaran.qi.Application;
import com.aldebaran.qi.Session;
import com.aldebaran.qi.helper.proxies.*;
import com.jcraft.jsch.*;
import org.freedesktop.gstreamer.*;
import org.freedesktop.gstreamer.elements.AppSink;
import javax.swing.*;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;

public class PepperSelfie {
    public static PepperSelfieConfig config;
    private WebStreamer webStreamer;
    private final Application application;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    // QiSDK Proxies
    private ALMemory memory;
    private ALMotion motion;
    private ALAnimatedSpeech animatedSpeech;
    private ALRobotPosture posture;
    private ALTextToSpeech tts;
    private ALSpeechRecognition speechRecognition;

    // GStreamer & SSH
    private Pipeline pipeline;
    private com.jcraft.jsch.Session sshSession;
    private BufferedImage capturedImage = null;
    
    private boolean isPreviewRunning = false;
    private boolean isWaitingForConfirmation = false;
    private static final String APP_HANDLE = "PepperSelfie_Gst";

    public PepperSelfie(String[] args) throws Exception {
        config = Util.loadConfiguration();
        String url = String.format("tcp://%s:%s", config.pepperIP(), config.pepperPort());
        this.application = new Application(args, url);
        Gst.init("PepperSelfie"); // GStreamer Engine starten
    }

    public static void main(String[] args) {
        try {
            PepperSelfie selfieApp = new PepperSelfie(args);
            selfieApp.run();
        } catch (Exception e) { e.printStackTrace(); }
    }

    public void run() throws Exception {
        application.start();
        initProxies(application.session());
        setupRobot();

        // Kopf-Touch startet den Prozess
        memory.subscribeToEvent("FrontTactilTouched", (touch) -> {
            if (touch instanceof Float && (Float) touch == 1.0f && !isPreviewRunning) {
                startSelfieProcess();
            }
        });

        // Bumper für Bestätigung/Abbruch
        memory.subscribeToEvent("RightBumperPressed", (val) -> {
            if (val instanceof Float && (Float) val == 1.0f && isWaitingForConfirmation) confirmAndPrint();
        });

        memory.subscribeToEvent("LeftBumperPressed", (val) -> {
            if (val instanceof Float && (Float) val == 1.0f && isWaitingForConfirmation) rejectAndRestart();
        });

        application.run();
    }

    private void initProxies(Session session) throws Exception {
        memory = new ALMemory(session);
        motion = new ALMotion(session);
        animatedSpeech = new ALAnimatedSpeech(session);
        posture = new ALRobotPosture(session);
        tts = new ALTextToSpeech(session);
        speechRecognition = new ALSpeechRecognition(session);
    }

    private void setupRobot() throws Exception {
        this.webStreamer = new WebStreamer();
        motion.wakeUp();
        posture.goToPosture("StandInit", 0.5f);
        tts.say("System bereit.");
    }

    private void startSelfieProcess() {
        try {
            isPreviewRunning = true;
            isWaitingForConfirmation = false;

            // 1. SSH-Sender auf Pepper & Lokalen Empfänger starten
            startRemoteGStreamer();
            startLocalPipeline();

            // Spracherkennung
            speechRecognition.setLanguage("German");
            ArrayList<String> vocabulary = new ArrayList<>();
            vocabulary.add("Foto aufnehmen");
            vocabulary.add("Selfie");
            speechRecognition.setVocabulary(vocabulary, false);

            memory.subscribeToEvent("WordRecognized", (words) -> {
                List<Object> data = (List<Object>) words;
                if (data.size() > 1 && (Float) data.get(1) > 0.45f && !isWaitingForConfirmation) {
                    takeAndShowPicture();
                }
            });

            animatedSpeech.say("Sagen Sie Foto aufnehmen.");
            speechRecognition.subscribe(APP_HANDLE);
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void startRemoteGStreamer() throws Exception {
        JSch jsch = new JSch();
        sshSession = jsch.getSession(config.pepperUser(), config.pepperIP(), config.sshPort());
        sshSession.setPassword(config.pepperPassword());
        sshSession.setConfig("StrictHostKeyChecking", "no");
        sshSession.connect();

        // Sendet HD-JPEGs vom Pepper
        String cmd = "gst-launch-0.10 v4l2src device=/dev/video0 ! video/x-raw-yuv,width=1280,height=960,framerate=10/1 ! ffmpegcolorspace ! jpegenc quality=80 ! tcpserversink port=5000";
        ChannelExec channel = (ChannelExec) sshSession.openChannel("exec");
        channel.setCommand(cmd);
        channel.connect();
    }

    private void startLocalPipeline() {
        String pipeDesc = "tcpclientsrc host=" + config.pepperIP() + " port=5000 ! jpegparse ! jpegdec ! videoconvert ! video/x-raw, format=BGRx ! appsink name=sink sync=false";
        pipeline = (Pipeline) Gst.parseLaunch(pipeDesc);
        AppSink sink = (AppSink) pipeline.getElementByName("sink");
        
        sink.set("emit-signals", true);
        sink.set("max-buffers", 1);
        sink.set("drop", true);

        sink.connect((AppSink.NEW_SAMPLE) elem -> {
            Sample sample = elem.pullSample();
            org.freedesktop.gstreamer.Buffer buffer = sample.getBuffer();
            Structure struct = sample.getCaps().getStructure(0);
            int w = struct.getInteger("width");
            int h = struct.getInteger("height");

            ByteBuffer bb = buffer.map(false);
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            int[] data = new int[w * h];
            bb.asIntBuffer().get(data);
            img.setRGB(0, 0, w, h, data, 0, w);
            buffer.unmap();
            sample.dispose();

            // Das aktuellste Bild zwischenspeichern
            this.capturedImage = img; 

            if (!isWaitingForConfirmation) {
                webStreamer.update(img);
            }
            return FlowReturn.OK;
        });
        pipeline.play();
    }

    private void takeAndShowPicture() {
        try {
            speechRecognition.pause(true);
            animatedSpeech.say("Drei... zwei... eins...");
            
            isWaitingForConfirmation = true;
            
            if (this.capturedImage != null) {
                // Branding auf das letzte Frame anwenden
                this.capturedImage = Util.applyBranding(this.capturedImage);
                webStreamer.update(this.capturedImage); // Standbild im Webbrowser
            }
            
            animatedSpeech.say("Foto fertig. Rechts zum Drucken, links zum Löschen.");
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void confirmAndPrint() {
        isWaitingForConfirmation = false;
        try {
            tts.say("Drucke.");
            new Thread(() -> {
                Util.printImage(capturedImage, config.width(), config.height(), config.numberImages(), config.printerIP());
            }).start();
            stopGStreamer();
            resetAfterAction();
        } catch (Exception e) {}
    }

    private void rejectAndRestart() {
        isWaitingForConfirmation = false;
        try {
            animatedSpeech.say("Foto gelöscht.");
            stopGStreamer();
            resetAfterAction();
            startSelfieProcess(); // Sofort neu starten
        } catch (Exception e) {}
    }

    private void resetAfterAction() {
        isPreviewRunning = false;
        try {
            speechRecognition.unsubscribe(APP_HANDLE);
        } catch (Exception e) {}
    }

    private void stopGStreamer() {
        if (pipeline != null) pipeline.stop();
        try {
            if (sshSession != null) {
                ChannelExec killCmd = (ChannelExec) sshSession.openChannel("exec");
                killCmd.setCommand("killall gst-launch-0.10");
                killCmd.connect();
                sshSession.disconnect();
            }
        } catch (Exception e) {}
    }

    public interface PepperSelfieConfig {
        // Verbindung zu Pepper (QiSDK & SSH)
        String pepperIP(); 
        String pepperPort();      // QiSDK Port (meist 9559)
        String pepperUser();      // SSH Username (meist "nao")
        String pepperPassword();  // SSH Passwort
        Integer sshPort();        // SSH Port (meist 22)

        // Drucker-Einstellungen
        String printerIP();
        Integer width();          // Breite in mm
        Integer height();         // Höhe in mm
        Integer numberImages();   // Anzahl Kopien

        // Branding-Einstellungen
        String imageText(); 
        String imageDate(); 
        Integer positionX();
    }
}
