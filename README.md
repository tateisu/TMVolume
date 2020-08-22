# TMVolume

tiny android app that remote control volume of RME TotalMix FX.

<img src="https://user-images.githubusercontent.com/333944/90967727-6b49ce00-e51e-11ea-8c3d-219ca92a7a66.png" width="320">

## Configuration
- Open TMVolume and memo the client wifi addr of your device.
- Open RME TotalMix FX
- Enable OSC Control in menu/Options.
- Open menu/Options/Settings.
- Wire Client and server addresses and ports

![image](https://user-images.githubusercontent.com/333944/89477015-1d676300-d7c7-11ea-9d20-beadf8675319.png)
![ss2](https://user-images.githubusercontent.com/333944/90967729-6e44be80-e51e-11ea-9c1a-5c3b47cb63cd.png)

- App's "Bus" means TotalMix's Hardware Inputs, Software Playbacks, Hardware Outputs. Maybe you want to choose `Output`.
- App's "Object Addr." is usually like as `/1/volume{channel}`. The channel designation is not the name, but the order of the volume fader you see in TotalMix. For example, if you see AN1/2 on the left edge, its channel number is 1, and the fader on the right next is 2.
- App's volume slider shows dB, but it sends TotalMix's fader position value. This is a limitation of TotalMix.

## See Also

- An OSC implementation chart can be downloaded from the RME website: http://www.rme-audio.de/download/osc_table_totalmix.zip
- this app uses JavaOSC: https://github.com/hoijui/JavaOSC 
