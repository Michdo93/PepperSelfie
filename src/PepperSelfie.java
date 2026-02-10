import com.aldebaran.qi.Application;
import com.aldebaran.qi.Session;
import com.aldebaran.qi.helper.proxies.*;
import org.cfg4j.provider.ConfigurationProvider;
import org.cfg4j.provider.ConfigurationProviderBuilder;
import org.cfg4j.source.files.FilesConfigurationSource;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class PepperSelfie {

    public static PepperSelfieConfig config; 
    
    private WebStreamer webStreamer;
    private final Application application;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    
    private ALMemory memory;
    private ALVideoDevice video;
    private ALMotion motion;
    private ALAnimatedSpeech animatedSpeech;
    private ALRobotPosture posture;
    // private ALSpeechRecognition speechRecognition; // Optional wieder einklammern falls nötig
    private ALBehaviorManager behaviorManager;

    private String cameraHandle;
    private ImagePlayer imageWindow;
    private boolean isStopping = false;
    private boolean isPreviewRunning = false;
    private static final String APP_HANDLE = "PepperSelfie_v3_Optimized";

    public PepperSelfie(String[] args) throws Exception {
        config = loadConfiguration();
        String url = String.format("tcp://%s:%s", config.pepperIP(), config.pepperPort());
        this.application = new Application(args, url);
    }

    public static void main(String[] args) {
        try {
            PepperSelfie selfieApp = new PepperSelfie(args);
            selfieApp.run();
        } catch (Exception e) {
            System.err.println("Kritischer Fehler beim Start:");
            e.printStackTrace();
        }
    }

    public void run() throws Exception {
        application.start();
        initProxies(application.session());
        setupRobot();
        
        // Kopf-Sensoren für den Start
        memory.subscribeToEvent("FrontTactilTouched", (touch) -> {
            if (touch instanceof Float && (Float) touch == 1.0f) {
                if (!isPreviewRunning) startSelfieProcess();
            }
        });

        memory.subscribeToEvent("RearTactilTouched", (touch) -> {
            if (touch instanceof Float && (Float) touch == 1.0f) stopApplication();
        });
        
        System.out.println("Anwendung bereit. Warte auf Kopf-Touch...");
        application.run();
    }

    private void initProxies(Session session) throws Exception {
        memory = new ALMemory(session);
        video = new ALVideoDevice(session);
        motion = new ALMotion(session);
        animatedSpeech = new ALAnimatedSpeech(session);
        posture = new ALRobotPosture(session);
        behaviorManager = new ALBehaviorManager(session);
    }

    private void setupRobot() throws Exception {
        imageWindow = new ImagePlayer("Pepper Selfie Preview", "Vorschau");
        
        // WebServer initialisieren (Real-Live Modus)
        this.webStreamer = new WebStreamer(); 
        
        motion.wakeUp();
        posture.goToPosture("StandInit", 0.5f);
        animatedSpeech.say("Das System ist bereit.");
    }

    private void startSelfieProcess() {
        try {
            isPreviewRunning = true;
            
            // REAL-LIVE: Echte Kamera abonnieren (Res 3 = HD, 11 = RGB)
            cameraHandle = video.subscribeCamera(APP_HANDLE, 0, 3, 11, 15);
            
            startLivePreview();
            
            animatedSpeech.say("Bitte stellen Sie sich vor mich auf.");

            // Falls du doch wieder Sprache nutzen willst, hier einklammern:
            /*
            memory.subscribeToEvent("WordRecognized", (words) -> {
                List<Object> data = (List<Object>) words;
                if (data.size() > 1 && (Float) data.get(1) > 0.4f) {
                    try { takeAndPrintPicture(); } catch (Exception e) { e.printStackTrace(); }
                }
            });
            */
            
            // Zum Testen: Foto nach 10 Sekunden automatisch auslösen
            scheduler.schedule(() -> {
                try { takeAndPrintPicture(); } catch (Exception e) { e.printStackTrace(); }
            }, 10, TimeUnit.SECONDS);

        } catch (Exception e) {
            System.err.println("Fehler beim Starten der Kamera: " + e.getMessage());
        }
    }

    private void startLivePreview() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (isStopping || cameraHandle == null) return;

                // REAL-LIVE: Bild vom Roboter holen
                List<Object> imageRemote = (List<Object>) video.getImageRemote(cameraHandle);
                if (imageRemote != null) {
                    BufferedImage img = Util.toBufferedImage_picture(imageRemote);
                    
                    // Update Fenster
                    if (imageWindow != null) imageWindow.update(img);
                    
                    // Update WebServer (Browser-Anzeige)
                    if (webStreamer != null) webStreamer.update(img);
                }
            } catch (Exception e) {
                // Einzelne Frame-Fehler ignorieren
            }
        }, 0, 66, TimeUnit.MILLISECONDS);
    }

    private void takeAndPrintPicture() throws Exception {
        ALTextToSpeech tts = new ALTextToSpeech(application.session());
        
        // 1. Kopf fixieren & Ausrichten (wichtig für scharfe Fotos)
        motion.setStiffnesses("Head", 1.0f);
        motion.angleInterpolationWithSpeed("HeadPitch", 0.0f, 0.2f);
        motion.angleInterpolationWithSpeed("HeadYaw", 0.0f, 0.2f);
        
        // 2. Countdown ohne Körperbewegung
        animatedSpeech.say("^mode(disabled) Achtung! Drei... zwei... eins...");
        
        // 3. Shutter & Blitz ausführen
        playCameraFeedback(); 
        
        // Kleiner technischer Wait, damit das Bild im Buffer den Blitz/Moment stabil hat
        Thread.sleep(100); 
        
        BufferedImage finalPhoto = imageWindow.getCurrentImage();
        
        if (finalPhoto != null) {
            tts.say("Das Foto ist im Kasten.");
            new Thread(() -> {
                Util.printImage(finalPhoto, config.width(), config.height(), config.numberImages(), config.printerIP());
            }).start();
            
            animatedSpeech.say("Es wird nun gedruckt.");
        }
    }

    private void playCameraFeedback() {
        try {
            ALTextToSpeech tts = new ALTextToSpeech(application.session());
            ALLeds leds = new ALLeds(application.session());

            // A. Verschluss schließt (LEDs aus)
            leds.fadeRGB("FaceLeds", 0x000000, 0.05f); 
            Thread.sleep(150);

            // B. Blitz & Klick (Der Moment der Aufnahme)
            leds.fadeRGB("FaceLeds", "white", 0.01f); 
            tts.say("\\vct=135\\ *Klick*"); 

            // C. Nachleuchten & Recovery (Asynchron via Scheduler)
            scheduler.schedule(() -> {
                try {
                    leds.fadeRGB("FaceLeds", "yellow", 0.05f);
                    scheduler.schedule(() -> {
                        try {
                            leds.fadeRGB("FaceLeds", "blue", 0.8f);
                        } catch (Exception e) {}
                    }, 200, TimeUnit.MILLISECONDS);
                } catch (Exception e) {}
            }, 100, TimeUnit.MILLISECONDS);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    private void stopApplication() {
        try {
            isStopping = true;
            isPreviewRunning = false;
            scheduler.shutdownNow();
            if (cameraHandle != null) video.unsubscribe(cameraHandle);
            if (imageWindow != null) imageWindow.dispose();
            motion.rest();
            application.stop();
            System.exit(0);
        } catch (Exception e) { System.exit(1); }
    }

    private PepperSelfieConfig loadConfiguration() {
        Path configPath = Paths.get(System.getProperty("user.dir"), "PepperSelfie.yaml");
        ConfigurationProvider provider = new ConfigurationProviderBuilder()
            .withConfigurationSource(new FilesConfigurationSource(() -> Collections.singletonList(configPath)))
            .build();
        return provider.bind("", PepperSelfieConfig.class);
    }

    // Erweitertes Interface für die YAML-Parameter
    public interface PepperSelfieConfig {
        String pepperIP();
        String pepperPort();
        String printerIP();
        Integer width();
        Integer height();
        Integer numberImages();
        String imageText();
        String imageDate();
        Integer positionX();
    }
}