# MisterWhisper
 

MisterWhisper is an open-source application designed to simplify your workflow by transforming spoken words into text in real-time. When you press a designated key (F8 or F9), the application records your voice, transcribes it using the powerful Whisper AI model (GPU-accelerated for fast and efficient recognition), and directly inputs the resulting text into the currently active software.

MisterWhisper supports over 100 languages, making it a robust multilingual transcription tool.

![MisterWhisper](https://raw.githubusercontent.com/openconcerto/MisterWhisper/refs/heads/main/tray.png)


# Features

- Quick voice transcription: Record and transcribe speech only while the designated key is pressed, like a walkie-talkie.

- Integration with active software: Automatically inputs transcribed text into the application you are currently using.

- GPU acceleration (optional): Powered by [whisper.cpp](https://github.com/ggerganov/whisper.cpp) for fast and accurate voice recognition.

- Local or remote : You can use the included Whisper transcription locally or connect to a remote service for transcription.

# Usage

There are two ways to start recording. 
- press the "F9" (you can change this hotkey) key quickly once to start, and then press F9 again to stop.
- hold down the F9 key. As soon as you release it, the recording will stop.

Note that the software detects silences and will start the transcription as soon as it detects one.

# Installation

- extract the provided zip (or jar) file or compile your own version of MisterWhisper
- optionaly, download a Whisper model (.bin file) from : https://huggingface.co/ggerganov/whisper.cpp/tree/main and copy it in the *models* folder 
(the best model is ggml-large-v3-turbo.bin)

# Windows (7,8,10,11..) versions

Download and extract the zip file from the Releases corresponding to your configuration :
- cpu version : if you have no GPU
- cuda version : for nVidia GPU
- vulkan version : should work with any modern GPU (be patient on first launch, shaders compilation takes time)

Just launch the *MisterWhisper.exe*.

Keep F9 pressed while talking, the text will be inserted into the currently active software after key release.

To access the settings or view the history, simply right-click on the icon in the taskbar.

# Linux and macOS versions

MisterWhisper requires a Java runtime to be installed (version 8 or newer) to use *MisterWhisper.jar*.

Providing a precompiled WhisperCpp library is not straightforward. 

You'll need to compile whisper.cpp and use the client-server mode :

`` 
whipser-server -l auto --port 9595 -t 8 -m "models/ggml-large-v3-turbo-q8_0.bin"
``

And

`` 
java -jar MisterWhisper.jar "http://127.0.0.1:9595/inference"
``

# Advanced Usage (client-server mode)
If you want to use a remote server, launch the *whisper.cpp* server on the remote machine, for example (the server ip is 192.168.1.100) :

`` 
server.exe -l auto --port 9595 -t 8 -m "models/ggml-large-v3-turbo-q8_0.bin"
``

On the local machine, add the remote url as first parameter : 

`` 
MisterWhisper.exe "http://192.168.1.100:9595/inference"
``

# Acknowledgements

Georgi Gerganov : For its state-of-the-art, efficient [whisper.cpp](https://github.com/ggerganov/whisper.cpp). Demonstrating that we don't need an abundance of low-quality Python software for AI tools.

OpenAI : For the open-source [Whisper](https://github.com/openai/whisper) project.

