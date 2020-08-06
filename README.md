# TMVolume

tiny android app that remote control volume of RME TotalMix FX.

## Configuration
- Open TMVolume and memo the client wifi addr of your device.
- Open RME TotalMix FX
- Enable OSC Control in menu/Options.
- Open menu/Options/Settings.
- Wire Client and server addresses and ports

![image](https://user-images.githubusercontent.com/333944/89477015-1d676300-d7c7-11ea-9d20-beadf8675319.png)
![image](https://user-images.githubusercontent.com/333944/89476976-09bbfc80-d7c7-11ea-8d1c-d82d12e94ccb.png)

- App's "Bus" means TotalMix's Hardware Inputs, Software Playbacks, Hardware Outputs. Maybe you want to choose `Output`.
- App's "Object Addr." is usually like as `/1/volume{channel}`. The channel designation is not the name, but the order of the volume faders you see in TotalMix. For example, if you see AN1/2 on the left edge, it is number 1, and the fader on the right next to it is number 2.
- App's volume slider sends fader position to TotalMix. You cannot specify the dB value to send. This is a limitation on the TotalMix side.
