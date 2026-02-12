import com.aldebaran.qi.Application;
import com.aldebaran.qi.Session;
import com.aldebaran.qi.helper.proxies.*;
import com.jcraft.jsch.*;
import org.freedesktop.gstreamer.*;
import org.freedesktop.gstreamer.elements.AppSink;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;

public class PepperSelfie {
	public static PepperSelfieConfig config;
	private WebStreamer webStreamer;
	private final Application application;
	private Pipeline pipeline;
	private com.jcraft.jsch.Session sshSession;
	private BufferedImage capturedImage = null;

	// Pepper API Proxies
	private ALMemory memory;
	private ALMotion motion;
	private ALAnimatedSpeech animatedSpeech;
	private ALRobotPosture posture;
	private ALTextToSpeech tts;
	private ALSpeechRecognition speechRecognition;

	private boolean isPreviewRunning = false;
	private boolean isWaitingForConfirmation = false;
	private static final String APP_HANDLE = "PepperSelfie_Gst";

	public PepperSelfie(String[] args) throws Exception {
		config = Util.loadConfiguration();
		String url = String.format("tcp://%s:%s", config.pepperIP(), config.pepperPort());
		this.application = new Application(args, url);
		Gst.init("PepperSelfie");
	}

	public static void main(String[] args) {
		try {
			PepperSelfie selfieApp = new PepperSelfie(args);
			selfieApp.run();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public void run() throws Exception {
		application.start();
		initProxies(application.session());
		setupRobot();

		// Events registrieren
		memory.subscribeToEvent("FrontTactilTouched", (Object touch) -> {
			if (touch instanceof Float && (Float) touch == 1.0f && !isPreviewRunning) {
				startSelfieProcess();
			}
		});

		memory.subscribeToEvent("RightBumperPressed", (Object val) -> {
			if (val instanceof Float && (Float) val == 1.0f && isWaitingForConfirmation)
				confirmAndPrint();
		});

		memory.subscribeToEvent("LeftBumperPressed", (Object val) -> {
			if (val instanceof Float && (Float) val == 1.0f && isWaitingForConfirmation)
				rejectAndRestart();
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
		tts.say("System bereit. Berühren Sie meinen Kopf für ein Foto.");
	}

	private void startSelfieProcess() {
		try {
			isPreviewRunning = true;
			isWaitingForConfirmation = false;

			startRemoteGStreamer();
			startLocalPipeline();

			speechRecognition.setLanguage("German");
			ArrayList<String> vocabulary = new ArrayList<>(Arrays.asList("Foto aufnehmen", "Selfie"));
			speechRecognition.setVocabulary(vocabulary, false);

			memory.subscribeToEvent("WordRecognized", (Object words) -> {
				List<Object> data = (List<Object>) words;
				if (data.size() > 1 && (Float) data.get(1) > 0.45f && !isWaitingForConfirmation) {
					takeAndShowPicture();
				}
			});

			animatedSpeech.say("Sagen Sie Foto aufnehmen.");
			speechRecognition.subscribe(APP_HANDLE);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private void startRemoteGStreamer() throws Exception {
		JSch jsch = new JSch();
		sshSession = jsch.getSession(config.pepperUser(), config.pepperIP(), config.sshPort());
		sshSession.setPassword(config.pepperPassword());

		java.util.Properties sshConfig = new java.util.Properties();
		sshConfig.put("StrictHostKeyChecking", "no");
		sshConfig.put("PreferredAuthentications", "password,keyboard-interactive");
		sshSession.setConfig(sshConfig);
		sshSession.connect(15000);

		// 1. Erst NUR killen
		ChannelExec killChannel = (ChannelExec) sshSession.openChannel("exec");
		killChannel.setCommand("killall gst-launch-0.10");
		killChannel.connect();

		// 2. WICHTIG: Dem Kernel 2 Sekunden Zeit geben, den Treiber zu entladen
		// (res_free verhindern)
		Thread.sleep(2000);

		// 3. Den Stream mit "queue" Puffer starten, um den Treiber zu entlasten
		// Wir fügen 'num-buffers' nicht hinzu, damit er endlos läuft,
		// aber wir nutzen 'v4l2src' ohne zusätzliche unicorn-Filter
		String cmd = "gst-launch-0.10 v4l2src device=/dev/video0 ! "
				+ "video/x-raw-yuv,width=640,height=480,framerate=15/1 ! "
				+ "jpegenc quality=50 ! multipartmux ! tcpserversink port=5000";

		ChannelExec channel = (ChannelExec) sshSession.openChannel("exec");
		channel.setCommand(cmd);
		channel.connect();
	}

	private void startLocalPipeline() {
		String pipeDesc = "tcpclientsrc host=" + config.pepperIP() + " port=5000 do-timestamp=true ! "
				+ "multipartdemux ! jpegparse ! jpegdec ! videoconvert ! "
				+ "video/x-raw,format=BGRx ! appsink name=sink sync=false";

		pipeline = (Pipeline) Gst.parseLaunch(pipeDesc);
		AppSink sink = (AppSink) pipeline.getElementByName("sink");
		sink.set("emit-signals", true);
		sink.set("max-buffers", 1);
		sink.set("drop", true);

		sink.connect((AppSink.NEW_SAMPLE) elem -> {
			Sample sample = elem.pullSample();
			if (sample == null)
				return FlowReturn.OK;

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

			this.capturedImage = img;
			if (!isWaitingForConfirmation)
				webStreamer.update(img);
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
				this.capturedImage = Util.applyBranding(this.capturedImage);
				webStreamer.update(this.capturedImage);
			}
			animatedSpeech.say("Foto fertig. Drücken Sie rechts am Fuß zum Drucken oder links zum Löschen.");
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private void confirmAndPrint() {
		isWaitingForConfirmation = false;
		try {
			tts.say("Das Foto wird gedruckt.");
			new Thread(() -> Util.printImage(capturedImage, config.width(), config.height(), config.numberImages(),
					config.printerIP())).start();
			stopGStreamer();
			resetAfterAction();
		} catch (Exception e) {
		}
	}

	private void rejectAndRestart() {
		isWaitingForConfirmation = false;
		try {
			animatedSpeech.say("In Ordnung, wir versuchen es noch einmal.");
			stopGStreamer();
			resetAfterAction();
			startSelfieProcess();
		} catch (Exception e) {
		}
	}

	private void resetAfterAction() {
		isPreviewRunning = false;
		try {
			speechRecognition.unsubscribe(APP_HANDLE);
		} catch (Exception e) {
		}
	}

	private void stopGStreamer() {
		if (pipeline != null)
			pipeline.stop();
		if (sshSession != null && sshSession.isConnected()) {
			try {
				ChannelExec kill = (ChannelExec) sshSession.openChannel("exec");
				kill.setCommand("killall gst-launch-0.10");
				kill.connect();
				sshSession.disconnect();
			} catch (Exception e) {
			}
		}
	}

	public interface PepperSelfieConfig {
		String pepperIP();

		String pepperPort();

		String pepperUser();

		String pepperPassword();

		Integer sshPort();

		String printerIP();

		Integer width();

		Integer height();

		Integer numberImages();

		String imageText();

		String imageDate();

		Integer positionX();
	}
}