# PepperSelfie

Interactive Java application with the Pepper robot (1.8a and NAOqi 2.5) for taking photos. The robot's camera stream is transmitted to a web server and displayed via a client for the photo.

## Features

* Starts a web server at the IP address of the server on which the application is running. The port for this web application has been set to `9559` because this port number is also known from Pepper itself.
  * **Please note**: The IP address of the server is not the same as Pepper's IP address! Please do not confuse the two.
* Transmits the Pepper's camera stream to the web server so that a display (monitor, projector, etc.) shows what an image should be taken of.
  * **Please note**: Since the camera stream from Peppers ALVideoDevice is severely delayed, blurry and slow, we use `GStreamer` instead.
* Only one camera stream is used for the display and for the image that is captured. The last frame of the camera stream when taking a "snapshot" corresponds to the photo that is captured.
* A special feature is that this application does not use the default printer, but rather an IP address for the printer. However, we use a trick here: Instead of directly implementing which IP, which port, and which protocol should be used for printing, we only use the IP address to compare which system driver for (multiple) printers has this IP address.
  * Depending on the printer, you may otherwise have to program and set the print format. Especially with small images, you often have to halve the print area, etc. This is then handled by the printer driver already stored in the system.
  * Another advantage is that you don't have to accidentally change the default printer in the system after printing. It is also possible that when a printer is turned off and then on again, the wrong printer is incorrectly selected for printing. The IP address always determines which printer must be used.
  * Not all printers are the same. An adapter solution that allows you to select multiple printer types is more complicated to program than using the printer's ready-made drivers.

## Customization

You can change the `PepperSelfie.yaml` file. As example it looks currently like this:

```
pepperIP: 192.168.0.41
pepperPort: 9559
pepperUser: nao
pepperPassword: nao
sshPort: 22

printerIP: 192.168.0.48
width: 50
height: 75
numberImages: 1

imageText: Labor Smart Home
imageDate: 11.02.2026
positionX: 450
```

The `pepperIP` and `pepperPort` should be changed because this values are for simulation. The default pepperPort is `9559` so you have to change this configuration to `9559`. The `pepperIP` you can receive from the Pepper robot by pressing its chest button.

## Add external Archives (Build & Run Environment)

To add all external `.jar` files for build and run environment, right-click on the **PepperSelfie** project folder and then select **Build Path > Add External Archives...** and all `.jar` files stored in the `lib` directory of this repository.

## Build

To ensure that the user receives only a **single file** (`e.g. PepperSelfie.jar`), you must select **Export as Runnable JAR File** in Eclipse. Here is the crucial path:

1. **Right-click on your project** -> `Export...` -> `Java` -> `Runnable JAR file`.
2. **Launch configuration**: Select the configuration with which you successfully tested your program in Eclipse (usually `PepperSelfie - PepperSelfie`).
3. **Export destination**: Choose your export destination and choose a name for your runnable JAR file (e.g. `PepperSelfie.jar`).
4. **Library handling**: This is the most important point for your goal. You have three options:
* `Extract required libraries into generated JAR` (choose this)
* `Package required libraries into generated JAR`
* `Copy required libraries into a sub-folder next to the generated JAR`
5. Click **Finish**

## Run

To run this `Java Archive` (`JAR`) you have to make sure that the `PepperSelfie.yaml` is in the same directory as the `PepperSelfie.jar` file. Then you have to run following

```
java -jar PepperSelfie.jar
```
