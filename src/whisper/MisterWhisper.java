package whisper;

import java.awt.AWTException;
import java.awt.CheckboxMenuItem;
import java.awt.Desktop;
import java.awt.Frame;
import java.awt.Image;
import java.awt.Menu;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.Robot;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.Window.Type;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Line;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import javax.sound.sampled.UnsupportedAudioFileException;
import javax.swing.ImageIcon;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;

public class MisterWhisper implements NativeKeyListener {

    private static final int MIN_AUDIO_DATA_LENGTH = (int) (16000 * 2.1);

    private Preferences prefs;

    // Whisper
    private LocalWhisperCPP w;
    private String model;
    private String remoteUrl;
    // Tray icon
    private TrayIcon trayIcon;
    private Image imageRecording;
    private Image imageTranscribing;
    private Image imageInactive;

    // Execution services
    private ExecutorService executorService = Executors.newSingleThreadExecutor();
    private ExecutorService audioService = Executors.newSingleThreadExecutor();

    // Audio capture
    private AudioFormat audioFormat;

    private boolean recording;
    private boolean transcribing;

    // History
    private List<String> history = new ArrayList<>();
    private List<ChangeListener> historyListeners = new ArrayList<>();

    // Hotkey for recording
    private String hotkey;
    private long recordingStartTime = 0;
    private boolean hotkeyPressed;
    private static final String[] ALLOWED_HOTKEYS = { "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11", "F12" };
    private static final int[] ALLOWED_HOTKEYS_CODE = { NativeKeyEvent.VC_F1, NativeKeyEvent.VC_F2, NativeKeyEvent.VC_F3, NativeKeyEvent.VC_F4, NativeKeyEvent.VC_F5, NativeKeyEvent.VC_F6,
            NativeKeyEvent.VC_F7, NativeKeyEvent.VC_F8, NativeKeyEvent.VC_F9, NativeKeyEvent.VC_F10, NativeKeyEvent.VC_F11, NativeKeyEvent.VC_F12 };

    // Action
    enum Action {
        COPY_TO_CLIPBOARD_AND_PASTE, TYPE_STRING, NOTHING
    }

    public MisterWhisper(String remoteUrl) throws FileNotFoundException, NativeHookException {
        if (MisterWhisper.ALLOWED_HOTKEYS.length != MisterWhisper.ALLOWED_HOTKEYS_CODE.length) {
            throw new IllegalStateException("ALLOWED_HOTKEYS size mismatch");
        }

        this.prefs = Preferences.userRoot().node("mister-whisper");
        this.hotkey = this.prefs.get("hotkey", "F9");
        this.model = this.prefs.get("model", "ggml-large-v3-turbo-q8_0.bin");

        GlobalScreen.registerNativeHook();
        GlobalScreen.addNativeKeyListener(this);

        // Create audio format
        float sampleRate = 16000.0F;
        int sampleSizeInBits = 16;
        int channels = 1;
        boolean signed = true;
        boolean bigEndian = false;
        this.audioFormat = new AudioFormat(sampleRate, sampleSizeInBits, channels, signed, bigEndian);

        this.remoteUrl = remoteUrl;
        if (remoteUrl == null) {

            File dir = new File("models");
            if (!dir.exists()) {
                dir.mkdirs();
            }
            boolean hasModels = false;
            for (File f : dir.listFiles()) {
                if (f.getName().endsWith(".bin")) {
                    hasModels = true;
                }
            }
            if (!hasModels) {
                JOptionPane.showMessageDialog(null,
                        "Please download a model (.bin file) from :\nhttps://huggingface.co/ggerganov/whisper.cpp/tree/main\n\n and copy it in :\n" + dir.getAbsolutePath());
                if (Desktop.isDesktopSupported()) {
                    final Desktop desktop = Desktop.getDesktop();
                    if (desktop.isSupported(Desktop.Action.BROWSE)) {
                        try {
                            desktop.browse(new URI("https://huggingface.co/ggerganov/whisper.cpp/tree/main"));
                        } catch (IOException | URISyntaxException e) {
                            e.printStackTrace();
                        }
                    }
                    if (desktop.isSupported(Desktop.Action.OPEN)) {
                        try {
                            desktop.open(dir);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }

                }
                System.exit(0);
            }

            if (!new File(dir, this.model).exists()) {
                for (File f : dir.listFiles()) {
                    if (f.getName().endsWith(".bin")) {
                        this.model = f.getName();
                        setModelPref(f.getName());
                        break;
                    }
                }
            }

            this.w = new LocalWhisperCPP(new File(dir, this.model));
            System.out.println("MisterWhisper using WhisperCPP with " + this.model);
        } else {
            System.out.println("MisterWhisper using remote speech to text service : " + remoteUrl);
        }
    }

    void createTrayIcon() {
        this.imageRecording = new ImageIcon(this.getClass().getResource("recording.png")).getImage();
        this.imageInactive = new ImageIcon(this.getClass().getResource("inactive.png")).getImage();
        this.imageTranscribing = new ImageIcon(this.getClass().getResource("transcribing.png")).getImage();

        this.trayIcon = new TrayIcon(this.imageInactive, "Press " + this.hotkey + " to record");
        this.trayIcon.setImageAutoSize(true);
        final SystemTray tray = SystemTray.getSystemTray();
        final Frame frame = new Frame("");
        frame.setUndecorated(true);
        frame.setType(Type.UTILITY);
        // Create a pop-up menu components
        final PopupMenu popup = createPopupMenu();
        this.trayIcon.setPopupMenu(popup);
        this.trayIcon.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseReleased(MouseEvent e) {
                stopRecording();
            }

            @Override
            public void mouseClicked(MouseEvent e) {

                if (e.isPopupTrigger()) {
                    frame.add(popup);
                    popup.show(frame, e.getXOnScreen(), e.getYOnScreen());

                }
            }

        });
        try {
            frame.setResizable(false);
            frame.setVisible(true);
            tray.add(this.trayIcon);
        } catch (AWTException e) {
            System.out.println("TrayIcon could not be added.\n" + e.getMessage());
        }

    }

    protected PopupMenu createPopupMenu() {
        final String strAction = this.prefs.get("action", "paste");

        final PopupMenu popup = new PopupMenu();

        CheckboxMenuItem autoPaste = new CheckboxMenuItem("Auto paste");
        autoPaste.setState(strAction.equals("paste"));
        popup.add(autoPaste);

        CheckboxMenuItem autoType = new CheckboxMenuItem("Auto type");
        autoType.setState(strAction.equals("type"));
        popup.add(autoType);

        final ItemListener typeListener = new ItemListener() {

            @Override
            public void itemStateChanged(ItemEvent e) {
                System.out.println("==" + e.toString() + " : " + e.getItem().getClass());
                // autoPaste.getState() + " " + autoType.getState());
                if (e.getSource().equals(autoPaste) && e.getStateChange() == ItemEvent.SELECTED) {
                    System.out.println("itemStateChanged() PASTE " + e.toString());
                    MisterWhisper.this.prefs.put("action", "paste");
                    autoType.setState(false);
                } else if (e.getSource().equals(autoType) && e.getStateChange() == ItemEvent.SELECTED) {
                    System.out.println("itemStateChanged() TYPE " + e.toString());
                    MisterWhisper.this.prefs.put("action", "type");
                    autoPaste.setState(false);
                } else {
                    MisterWhisper.this.prefs.put("action", "nothing");
                }

                try {
                    MisterWhisper.this.prefs.sync();
                } catch (BackingStoreException e1) {
                    e1.printStackTrace();
                    JOptionPane.showMessageDialog(null, "Cannot save preferences\n" + e1.getMessage());
                }
            }
        };
        autoPaste.addItemListener(typeListener);
        autoType.addItemListener(typeListener);

        CheckboxMenuItem detectSilece = new CheckboxMenuItem("Silence detection");
        detectSilece.setState(this.prefs.getBoolean("silence-detection", false));
        detectSilece.addItemListener(new ItemListener() {

            @Override
            public void itemStateChanged(ItemEvent e) {
                MisterWhisper.this.prefs.putBoolean("silence-detection", detectSilece.getState());
                try {
                    MisterWhisper.this.prefs.sync();
                } catch (BackingStoreException e1) {
                    e1.printStackTrace();
                    JOptionPane.showMessageDialog(null, "Cannot save preferences\n" + e1.getMessage());
                }
            }
        });
        popup.add(detectSilece);
        Menu hotkeysMenu = new Menu("Keyboard shortcut");

        for (final String key : MisterWhisper.ALLOWED_HOTKEYS) {
            final CheckboxMenuItem hotkeyMenuItem = new CheckboxMenuItem(key);
            if (this.hotkey.equals(key)) {
                hotkeyMenuItem.setState(true);
            }
            hotkeysMenu.add(hotkeyMenuItem);
            hotkeyMenuItem.addItemListener(new ItemListener() {

                @Override
                public void itemStateChanged(ItemEvent e) {
                    if (hotkeyMenuItem.getState()) {
                        MisterWhisper.this.hotkey = key;
                        MisterWhisper.this.prefs.put("hotkey", MisterWhisper.this.hotkey);
                        try {
                            MisterWhisper.this.prefs.sync();
                        } catch (BackingStoreException e1) {
                            e1.printStackTrace();
                            JOptionPane.showMessageDialog(null, "Cannot save preferences\n" + e1.getMessage());
                        }
                        hotkeyMenuItem.setState(false);
                        MisterWhisper.this.trayIcon.setToolTip("Press " + MisterWhisper.this.hotkey + " to record");

                    }
                }
            });
        }

        if (this.remoteUrl == null) {
            Menu modelMenu = new Menu("Models");

            final File dir = new File("models");
            List<CheckboxMenuItem> allModels = new ArrayList<>();
            if (new File(dir, this.model).exists()) {
                for (File f : dir.listFiles()) {
                    final String name = f.getName();
                    if (name.endsWith(".bin")) {
                        final boolean selected = this.model.equals(name);
                        String cleanName = name.replace(".bin", "");
                        cleanName = cleanName.replace(".bin", "");
                        cleanName = cleanName.replace("ggml", "");
                        cleanName = cleanName.replace("-", " ");
                        cleanName = cleanName.trim();
                        final CheckboxMenuItem modelItem = new CheckboxMenuItem(cleanName);

                        modelItem.setState(selected);

                        modelItem.addItemListener(new ItemListener() {

                            @Override
                            public void itemStateChanged(ItemEvent e) {
                                if (modelItem.getState()) {
                                    // Deselected others
                                    for (CheckboxMenuItem item : allModels) {
                                        if (item != modelItem) {
                                            item.setState(false);
                                        }
                                    }
                                    // Apply model
                                    MisterWhisper.this.model = f.getName();
                                    setModelPref(MisterWhisper.this.model);
                                    try {
                                        MisterWhisper.this.w = new LocalWhisperCPP(f);
                                    } catch (FileNotFoundException e1) {
                                        JOptionPane.showMessageDialog(null, e1.getMessage());
                                        e1.printStackTrace();
                                    }
                                }
                            }

                        });
                        allModels.add(modelItem);
                        modelMenu.add(modelItem);
                    }
                }
            }

            popup.add(modelMenu);
        }
        popup.add(hotkeysMenu);

        final Menu audioInputsItem = new Menu("Audio inputs");
        String audioDevice = this.prefs.get("audio.device", "");
        String previsouAudipDevice = this.prefs.get("audio.device.previous", "");
        // Get available audio input devices

        List<String> mixers = getInputsMixerNames();
        if (!mixers.isEmpty()) {
            String currentAudioDevice = "";
            if (!audioDevice.isEmpty() && mixers.contains(audioDevice)) {
                currentAudioDevice = audioDevice;
            } else if (!previsouAudipDevice.isEmpty() && mixers.contains(previsouAudipDevice)) {
                currentAudioDevice = previsouAudipDevice;
            } else {
                currentAudioDevice = mixers.get(0);
                this.prefs.put("audio.device", currentAudioDevice);
                try {
                    this.prefs.sync();
                } catch (BackingStoreException e1) {
                    e1.printStackTrace();
                }
            }
            Collections.sort(mixers);
            List<CheckboxMenuItem> all = new ArrayList<>();
            for (String name : mixers) {

                CheckboxMenuItem menuItem = new CheckboxMenuItem(name);
                if (currentAudioDevice.equals(name)) {
                    menuItem.setState(true);
                }
                audioInputsItem.add(menuItem);
                all.add(menuItem);
                // Add action listener to each menu item
                menuItem.addItemListener(new ItemListener() {

                    @Override
                    public void itemStateChanged(ItemEvent e) {
                        if (menuItem.getState()) {

                            for (CheckboxMenuItem m : all) {
                                final boolean selected = m.getLabel().equals(name);
                                m.setState(selected);

                            }
                            // Set preference
                            MisterWhisper.this.prefs.put("audio.device.previous", MisterWhisper.this.prefs.get("audio.device", ""));
                            MisterWhisper.this.prefs.put("audio.device", name);
                            try {
                                MisterWhisper.this.prefs.sync();
                            } catch (BackingStoreException e1) {
                                e1.printStackTrace();
                            }
                        }
                    }
                });

            }
        }
        popup.add(audioInputsItem);
        final MenuItem historyItem = new MenuItem("History");

        popup.add(historyItem);

        popup.addSeparator();
        MenuItem exitItem = new MenuItem("Exit");
        popup.add(exitItem);
        exitItem.addActionListener(new ActionListener() {

            @Override
            public void actionPerformed(ActionEvent e) {
                System.exit(0);

            }
        });
        historyItem.addActionListener(new ActionListener() {

            @Override
            public void actionPerformed(ActionEvent e) {
                HistoryFrame f = new HistoryFrame(MisterWhisper.this);
                f.setSize(600, 800);
                f.setLocationRelativeTo(null);
                f.setVisible(true);

            }
        });
        return popup;
    }

    private List<String> getInputsMixerNames() {
        final List<String> names = new ArrayList<>();
        final Mixer.Info[] mixers = AudioSystem.getMixerInfo();

        for (Mixer.Info mixerInfo : mixers) {
            final Mixer mixer = AudioSystem.getMixer(mixerInfo);
            final Line.Info[] targetLines = mixer.getTargetLineInfo();
            boolean ok = false;
            for (Line.Info lineInfo : targetLines) {
                if (lineInfo.getLineClass().getName().contains("TargetDataLine")) {
                    ok = true;
                    break;
                }
            }
            if (ok) {

                names.add(mixerInfo.getName());
            }
        }
        return names;
    }

    private TargetDataLine getFirstTargetDataLine() {
        final Mixer.Info[] mixers = AudioSystem.getMixerInfo();
        // Return first
        for (Mixer.Info mixerInfo : mixers) {
            final Mixer mixer = AudioSystem.getMixer(mixerInfo);
            final Line.Info[] targetLines = mixer.getTargetLineInfo();

            Line.Info lInfo = null;
            for (Line.Info lineInfo : targetLines) {
                if (lineInfo.getLineClass().getName().contains("TargetDataLine")) {
                    try {
                        return (TargetDataLine) mixer.getLine(lInfo);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }

        }
        return null;
    }

    private TargetDataLine getTargetDataLine(String audioDevice) {
        if (audioDevice == null || audioDevice.isEmpty()) {
            return null;
        }
        final Mixer.Info[] mixers = AudioSystem.getMixerInfo();

        for (Mixer.Info mixerInfo : mixers) {
            final Mixer mixer = AudioSystem.getMixer(mixerInfo);
            final Line.Info[] targetLines = mixer.getTargetLineInfo();

            Line.Info lInfo = null;
            for (Line.Info lineInfo : targetLines) {
                if (lineInfo != null && lineInfo.getLineClass().getName().contains("TargetDataLine")) {
                    lInfo = lineInfo;
                    if (mixerInfo.getName().equals(audioDevice)) {
                        try {
                            return (TargetDataLine) mixer.getLine(lInfo);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        }

        return null;
    }

    @Override
    public void nativeKeyPressed(NativeKeyEvent e) {
        if (this.hotkeyPressed) {
            return;
        }

        final int length = MisterWhisper.ALLOWED_HOTKEYS_CODE.length;
        for (int i = 0; i < length; i++) {
            if (MisterWhisper.ALLOWED_HOTKEYS_CODE[i] == e.getKeyCode() && this.hotkey.equals(MisterWhisper.ALLOWED_HOTKEYS[i])) {
                this.hotkeyPressed = true;
                SwingUtilities.invokeLater(new Runnable() {

                    @Override
                    public void run() {
                        final String strAction = MisterWhisper.this.prefs.get("action", "paste");
                        Action action = Action.NOTHING;
                        if (strAction.equals("paste")) {
                            action = Action.COPY_TO_CLIPBOARD_AND_PASTE;
                        } else if (strAction.equals("type")) {
                            action = Action.TYPE_STRING;
                        }

                        if (!isRecording()) {
                            MisterWhisper.this.recordingStartTime = System.currentTimeMillis();
                            startRecording(action);
                        } else {
                            stopRecording();
                        }
                    }
                });
                break;
            }
        }

    }

    @Override
    public void nativeKeyReleased(NativeKeyEvent e) {
        final int length = MisterWhisper.ALLOWED_HOTKEYS_CODE.length;
        for (int i = 0; i < length; i++) {
            if (MisterWhisper.ALLOWED_HOTKEYS_CODE[i] == e.getKeyCode() && this.hotkey.equals(MisterWhisper.ALLOWED_HOTKEYS[i])) {
                this.hotkeyPressed = false;

                SwingUtilities.invokeLater(new Runnable() {

                    @Override
                    public void run() {
                        long delta = System.currentTimeMillis() - MisterWhisper.this.recordingStartTime;
                        if (delta > 300) {
                            stopRecording();
                        }
                    }
                });
                break;
            }
        }

    }

    @Override
    public void nativeKeyTyped(NativeKeyEvent e) {
        // Not used but required by the interface
    }

    private void startRecording(Action action) {
        System.out.println("MisterWhisper.startRecording()" + action);
        if (isRecording()) {
            // Prevent multiple recordings
            return;
        }

        setRecording(true);
        try {
            String audioDevice = this.prefs.get("audio.device", "");
            String previsouAudipDevice = this.prefs.get("audio.device.previous", "");

            // Create a thread to capture the audio data
            this.audioService.execute(new Runnable() {

                @Override
                public void run() {
                    TargetDataLine targetDataLine;
                    try {
                        targetDataLine = getTargetDataLine(audioDevice);
                        if (targetDataLine == null) {
                            targetDataLine = getTargetDataLine(previsouAudipDevice);
                            if (targetDataLine == null) {
                                targetDataLine = getFirstTargetDataLine();
                            } else {
                                System.out.println("Using previous audio device : " + previsouAudipDevice);
                            }
                            if (targetDataLine == null) {
                                JOptionPane.showMessageDialog(null, "Cannot find any input audio device");
                                setRecording(false);
                                return;
                            } else {
                                System.out.println("Using default audio device");
                            }
                        } else {
                            System.out.println("Using audio device : " + audioDevice);
                        }

                        final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                        try {
                            targetDataLine.open(MisterWhisper.this.audioFormat);
                            targetDataLine.start();

                            setRecording(true);

                            // 0.25s
                            byte[] data = new byte[8000];
                            boolean detectSilence = MisterWhisper.this.prefs.getBoolean("silence-detection", false);
                            if (detectSilence) {
                                while (isRecording()) {
                                    int numBytesRead = targetDataLine.read(data, 0, data.length);
                                    if (numBytesRead > 0) {
                                        boolean silence = detectSilence(data, numBytesRead, 500);

                                        if (silence) {
                                            byte[] audioData = byteArrayOutputStream.toByteArray();
                                            byteArrayOutputStream.reset();
                                            MisterWhisper.this.executorService.execute(new Runnable() {

                                                @Override
                                                public void run() {
                                                    transcribe(audioData, action, false);
                                                }
                                            });
                                        } else {
                                            byteArrayOutputStream.write(data, 0, numBytesRead);
                                        }

                                    }
                                }
                            } else {
                                while (isRecording()) {
                                    int numBytesRead = targetDataLine.read(data, 0, data.length);
                                    if (numBytesRead > 0) {
                                        byteArrayOutputStream.write(data, 0, numBytesRead);
                                    }
                                }
                            }

                        } catch (LineUnavailableException e) {
                            System.out.println("Audio input device not available (used by an other process?)");
                        } catch (Exception e) {
                            e.printStackTrace();
                        } finally {
                            try {
                                targetDataLine.stop();
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                            try {
                                targetDataLine.close();
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                        final byte[] audioData = byteArrayOutputStream.toByteArray();
                        setRecording(false);

                        MisterWhisper.this.executorService.execute(new Runnable() {

                            @Override
                            public void run() {
                                transcribe(audioData, action, true);
                            }
                        });

                    } catch (Exception e) {
                        e.printStackTrace();

                    }
                    setRecording(false);
                    setTranscribing(false);

                }
            });

        } catch (Exception e) {
            setRecording(false);
            e.printStackTrace();
            JOptionPane.showMessageDialog(null, "Error starting recording: " + e.getMessage());
        }
    }

    public void transcribe(byte[] audioData, final Action action, boolean isEndOfCapture) {
        if (detectSilence(audioData, audioData.length, 100)) {
            return;
        }
        if (audioData.length < MIN_AUDIO_DATA_LENGTH) {
            byte[] n = new byte[MIN_AUDIO_DATA_LENGTH];
            System.arraycopy(audioData, 0, n, 0, audioData.length);
            audioData = n;
        }

        setTranscribing(true);

        // Save the recorded audio to a WAV file
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String fileName = timestamp + ".wav";

        try (AudioInputStream audioInputStream = new AudioInputStream(new ByteArrayInputStream(audioData), this.audioFormat, audioData.length / this.audioFormat.getFrameSize())) {

            final File out = File.createTempFile("rec_", fileName);
            AudioSystem.write(audioInputStream, AudioFileFormat.Type.WAVE, out);

            String str;
            if (MisterWhisper.this.remoteUrl == null) {
                str = process(out, action);
            } else {
                str = processRemote(out, action);
            }
            str = str.replace('\n', ' ');
            str = str.replace('\r', ' ');
            str = str.replace('\t', ' ');
            str = str.trim();
            if (!isEndOfCapture) {
                str += " ";
            }
            final String finalStr = str;

            SwingUtilities.invokeLater(new Runnable() {

                @Override
                public void run() {
                    if (action.equals(Action.TYPE_STRING)) {
                        try {
                            RobotTyper typer = new RobotTyper();
                            System.out.println("Typing : " + finalStr);
                            typer.typeString(finalStr, 11);
                        } catch (AWTException e) {
                            e.printStackTrace();
                        }
                    } else if (action.equals(Action.COPY_TO_CLIPBOARD_AND_PASTE)) {
                        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
                        Transferable previous;
                        try {
                            previous = clipboard.getContents(null);
                        } catch (Exception e) {
                            previous = null;
                            try {
                                GlobalScreen.registerNativeHook();
                            } catch (NativeHookException e1) {
                                e1.printStackTrace();
                            }
                            System.out.println("Warning : cannot get previous clipboard content");
                        }
                        final Transferable toPaste = previous;
                        clipboard.setContents(new StringSelection(finalStr), null);
                        try {
                            Robot robot = new Robot();
                            System.out.println("Pasting : " + finalStr);
                            robot.keyPress(KeyEvent.VK_CONTROL);
                            robot.keyPress(KeyEvent.VK_V);
                            try {
                                Thread.sleep(20);
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                            robot.keyRelease(KeyEvent.VK_V);
                            robot.keyRelease(KeyEvent.VK_CONTROL);
                            System.out.println("Pasting : " + finalStr + " DONE");

                        } catch (AWTException e) {
                            e.printStackTrace();
                        }
                        if (toPaste != null) {
                            Thread t = new Thread(new Runnable() {
                                public void run() {
                                    if (toPaste != null) {
                                        try {
                                            Thread.sleep(1000);
                                        } catch (InterruptedException e) {
                                            e.printStackTrace();
                                        }
                                        System.out.println("Restoring previous clipboard content");
                                        clipboard.setContents(toPaste, null);

                                    }
                                }
                            });
                            t.start();
                        }

                    }
                    // Invoke later to be sure paste is done
                    SwingUtilities.invokeLater(new Runnable() {

                        @Override
                        public void run() {
                            MisterWhisper.this.history.add(finalStr);
                            fireHistoryChanged();
                        }
                    });
                }
            });

            boolean deleted = out.delete();
            if (!deleted) {
                Logger.getGlobal().warning("cannot delete " + out.getAbsolutePath());
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(null, "Error processing record : " + e.getMessage());
            e.printStackTrace();
        }

        setTranscribing(false);

    }

    protected synchronized void setTranscribing(boolean b) {
        this.transcribing = b;
        updateIcon();
    }

    public synchronized boolean isTranscribing() {
        return this.transcribing;
    }

    public synchronized boolean isRecording() {
        return this.recording;
    }

    public synchronized void setRecording(boolean b) {
        this.recording = b;
        updateIcon();
    }

    private void updateIcon() {
        SwingUtilities.invokeLater(new Runnable() {

            @Override
            public void run() {
                if (isRecording()) {
                    MisterWhisper.this.trayIcon.setImage(MisterWhisper.this.imageRecording);
                } else {
                    if (isTranscribing()) {
                        MisterWhisper.this.trayIcon.setImage(MisterWhisper.this.imageTranscribing);
                    } else {
                        MisterWhisper.this.trayIcon.setImage(MisterWhisper.this.imageInactive);
                    }

                }

            }
        });

    }

    private String process(File out, Action action) throws IOException, UnsupportedAudioFileException {
        long t1 = System.currentTimeMillis();

        String response = this.w.transcribe(out);
        System.out.println("Response local : " + response);
        long t2 = System.currentTimeMillis();
        System.out.println("Process time  " + (t2 - t1) + " ms");
        return response.trim();

    }

    private String processRemote(File out, Action action) throws IOException {
        long t1 = System.currentTimeMillis();
        String string = new RemoteWhisperCPP(this.remoteUrl).transcribe(out, 0.0, 0.01);
        System.out.println("Response remote : " + string);
        long t2 = System.currentTimeMillis();
        System.out.println("Response  " + (t2 - t1) + " ms");
        return string.trim();

    }

    private void stopRecording() {
        if (!this.isRecording()) {
            return;
        }
        setRecording(false);
    }

    public void setModelPref(String name) {

        this.prefs.put("model", name);
        try {
            this.prefs.flush();
        } catch (BackingStoreException e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(null, "Cannot save preferences");
        }
    }

    public void addHistoryListener(ChangeListener l) {
        this.historyListeners.add(l);
    }

    public void removeHistoryListener(ChangeListener l) {
        this.historyListeners.remove(l);
    }

    public void clearHistory() {
        this.history.clear();
        fireHistoryChanged();
    }

    public void fireHistoryChanged() {
        for (ChangeListener l : this.historyListeners) {
            l.stateChanged(new ChangeEvent(this));
        }
    }

    public List<String> getHistory() {
        return this.history;
    }

    public static void main(String[] args) {

        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (ClassNotFoundException | InstantiationException | IllegalAccessException | UnsupportedLookAndFeelException e) {
                e.printStackTrace();
            }
            try {
                String url = null;
                if (args.length >= 1 && !args[0].startsWith("-D")) {
                    url = args[0];
                }
                MisterWhisper r = new MisterWhisper(url);
                r.createTrayIcon();
            } catch (Throwable e) {
                JOptionPane.showMessageDialog(null, "Error :\n" + e.getMessage());
                e.printStackTrace();
            }
        });

    }

    private static boolean detectSilence(byte[] buffer, int bytesRead, int threshold) {
        int maxAmplitude = 0;
        // 16-bit audio = 2 bytes per sample
        for (int i = 0; i < bytesRead; i += 2) {
            int sample = (buffer[i + 1] << 8) | (buffer[i] & 0xFF);
            maxAmplitude = Math.max(maxAmplitude, Math.abs(sample));
        }
        if (maxAmplitude > threshold) {
            System.out.println("MisterWhisper.detectSilence() NOT SILENCE : " + maxAmplitude);
        }
        return maxAmplitude < threshold;
    }
}
