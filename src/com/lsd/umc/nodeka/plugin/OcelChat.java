package com.lsd.umc.nodeka.plugin;

import com.lsd.umc.Client;
import com.lsd.umc.script.ScriptInterface;
import com.lsd.umc.util.AnsiTable;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OcelChat {

    private ScriptInterface script;
    private chat chat = null;
    private BufferedReader inputFromServer = null;
    private PrintWriter outputToServer = null;

    private String username;
    private String password;
    private String serverAddress;
    private Socket socket;
    private int port;
    private String tag = null;

    private final String version = "1.11.00";
    private static final Pattern health = Pattern.compile("(?:\\[?\\s?(?:H|Health|HP)\\s?:?\\s?(\\d+)(?:\\/(\\d+))?\\]?\\s?)");
    private static final Pattern pkFlag = Pattern.compile("\\[1;31mPKer");
    private static final Pattern pvpCombat = Pattern.compile("(?<![a-z'-] )([A-Z][a-z'-]++)[^(A-Z]*\\(([^)]+)\\)[^A-Z\\v]+([A-Z][a-z'-]++)(?!\\s[A-Za-z'-])[^(A-Z]*\\(([^)]+)\\)");
    private static final Pattern condition = Pattern.compile("\\[1;3[0-9]m(.+)");

    /*
     [L:0] R:Draalik X:2000000000 G:205023444 A:-372
     [Lag: 0] [Reply: guide] [Align: 797]
     L:0 R: X:442159262
     [L:0] [Ocellaris H:43028/43028 M:8884/8884 S:5001/5001 E:21049/21049] [A:-1000] []
     */
    private static final Pattern nonCombatPrompt = Pattern.compile("^(?:\\[?(?:Lag|L):\\s?\\d+\\]?)\\s\\[?(?:R|Reply|.+ H):?\\s?");

    private static final Pattern huri = Pattern.compile("^Huri\\!\\! (.+) is bound by fear \\(locked in combat\\) by your rage\\!");
    private static final Pattern huriAffected = Pattern.compile("^Huri\\!\\! You are bound by fear \\(locked in combat\\) by (.+)'s rage\\!");
    private static final Pattern continuum = Pattern.compile("^You wrap a continuum of combat around (.+) - (?:.+) is combat locked\\!");
    private static final Pattern continuumAffected = Pattern.compile("(.+) locks you in a continuum of combat\\!$");
    private static final Pattern taktikosAffected = Pattern.compile("(.+) maneuvers around you and locks you in battle\\.$");
    private static final Pattern glacious = Pattern.compile("^You hold out a glacious stones in your (?:.+) and your (.+) is restored\\!$");
    private static final Pattern watchGlacious = Pattern.compile("(.+) holds out a glacious stones in (?:.+)\\. With a blinding light the stone vanishes\\.$");

    private int currHP = 0;
    private int maxHP = 0;
    private double healthRatio = 0.0;
    private int currMaxHP = 0;
    private boolean PKFlag;
    private boolean inPVP;
    private String PVPTank;
    private String PVPTankCondition;
    private String PVPOpponent;
    private String PVPOpponentCondition;
    private Client client;
    private boolean report;
    private boolean reported;
    private String[] blacklist;

    public void init(ScriptInterface script) {
        try {
            this.script = script;
            Field privateClient = script.getClass().getDeclaredField("client");
            privateClient.setAccessible(true);
            client = (Client) privateClient.get(script);

            script.print("");
            script.print(AnsiTable.getCode("yellow") + "OcelChat Client Loaded.\001");
            script.print(AnsiTable.getCode("yellow") + "Developed by Ocellaris.\001");
            script.registerCommand("OcelChat", "com.lsd.umc.nodeka.plugin.OcelChat", "menu");
        } catch (IllegalArgumentException | IllegalAccessException ex) {
        } catch (NoSuchFieldException | SecurityException ex) {
        }
        readPropertiesFile();
    }

    public void readPropertiesFile() {
        Path p = Paths.get("OcelBot.properties");
        Properties prop = new Properties();
        if (Files.exists(p)) {
            try {
                FileInputStream fis = new FileInputStream("OcelBot.properties");
                prop.load(fis);
                fis.close();
                String list = prop.getProperty("OcelChat_blacklist");
                blacklist = list.split(",");
                for (String s : blacklist) {
                    script.print(AnsiTable.getCode("yellow") + "Blacklisted: " + s);
                }

                serverAddress = prop.getProperty("OcelChat_server", "108.198.29.168").trim();
                port = Integer.parseInt(prop.getProperty("OcelChat_port", "9002").trim());
                username = prop.getProperty("OcelChat_username").trim();
                password = prop.getProperty("OcelChat_password").trim();
                tag = prop.getProperty("OcelChat_tag").trim();

            } catch (IOException e) {
                script.print(AnsiTable.getCode("light red") + "OcelChat: Error - unable to read OcelBot.properties file.");
            } finally {
            }
        } else {
            try {
                Files.createFile(p);
                try (OutputStream fileOutput = new FileOutputStream("OcelBot.properties")) {
                    prop.setProperty("OcelChat_server", "108.198.29.168");
                    prop.setProperty("OcelChat_port", "9002");
                    prop.setProperty("OcelChat_username", script.getVariable("UMC_NAME"));
                    prop.setProperty("OcelChat_password", script.getVariable("UMC_NAME"));
                    prop.setProperty("OcelChat_tag", "");
                    prop.setProperty("OcelChat_blacklist", "Ent,Goolm,Ghor,Tsilloa");

                    prop.store(fileOutput, "OcelBot Properties - For lists seperate with a comma");
                    fileOutput.close();
                }
            } catch (IOException e) {
                script.print(AnsiTable.getCode("light red") + "OcelChat: Error - unable to create OcelBot.properties file.");
            }
            script.print(p.toAbsolutePath().toString());
            script.print(AnsiTable.getCode("light red") + "OcelChat: Error - unable to load OcelBot.properties file.");
        }
    }

    public String quit(String cmd) throws IOException {
        this.outputToServer.close();
        this.inputFromServer.close();
        this.socket.close();

        this.script.captureMatch(AnsiTable.getCode("light red") + "OcelChat: You have left the server.");
        return "";
    }

    public String commandQuit(String cmd) throws IOException {
        this.outputToServer.println("LEAVE");
        this.outputToServer.close();
        this.inputFromServer.close();
        this.socket.close();

        this.script.unregisterCommand("ochat");
        this.script.unregisterCommand("quit");
        return "";
    }

    public Socket getSocket() {
        return this.socket;
    }

    public String menu(String args) {
        if (this.chat != null && this.chat.isAlive()) {
            script.captureMatch(AnsiTable.getCode("light red") + "OcelChat: Server connection already active.");
            return "";
        }

        List<String> argArray = new ArrayList<>();
        Matcher m = Pattern.compile("([^\"]\\S*|\".+?\")\\s*").matcher(args);
        while (m.find()) {
            argArray.add(m.group(1).replace("\"", ""));
        }

        if (argArray.isEmpty() || argArray.size() > 3 || "".equals(argArray.get(0))) {
            script.capture(AnsiTable.getCode("light red") + "--------------------------------------------------------------------------\001");
            script.capture(AnsiTable.getCode("yellow") + "Syntax:\001");
            script.capture(AnsiTable.getCode("white") + " > #OcelChat" + AnsiTable.getCode("yellow") + " connect" + AnsiTable.getCode("white") + "\001");
            script.capture(AnsiTable.getCode("light red") + "--------------------------------------------------------------------------\001");
            return "";
        }

        if ("info".equals(argArray.get(0))) {
            script.capture(AnsiTable.getCode("white") + "Server Address: " + this.serverAddress + "\n"
                    + AnsiTable.getCode("white") + "Server Port: " + this.port + "\n"
                    + AnsiTable.getCode("white") + "Client Name: " + this.username + "\n"
                    + AnsiTable.getCode("white") + "Client Password: " + this.password + "\001");
        }

        if ("connect".equals(argArray.get(0))) {
            try {
                readPropertiesFile();
                if (username.isEmpty() || password.isEmpty() || username == null || password == null) {
                    script.capture(AnsiTable.getCode("light red") + "OcelChat: Error - Unable to connect, please update your OcelBot.properties file.");
                }
                connect();
                this.chat = new chat(this);
                this.chat.start();
            } catch (Exception ex) {
                script.capture(Arrays.toString(ex.getStackTrace()));
                script.capture(AnsiTable.getCode("light red") + "OcelChat: Error - Can't reach the server.");
            }
        }

        return "";
    }

    public BufferedReader getIn() {
        return this.inputFromServer;
    }

    public PrintWriter getOut() {
        return this.outputToServer;
    }

    /*
     DO NOT EVER TOUCH!
     */
    public void connect() throws IOException {
        socket = new Socket(this.serverAddress, this.port);
        socket.setTrafficClass(16);
        socket.setPerformancePreferences(0, 5, 0);

        this.inputFromServer = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        this.outputToServer = new PrintWriter(socket.getOutputStream(), true);
    }

    public void processServerResponse(String line) {
        if (line.equals("VERSION")) {
            this.outputToServer.println(this.version);
            this.script.registerCommand("quit", "com.lsd.umc.nodeka.plugin.OcelChat", "commandQuit");
        } else if (line.equals("PASSWORD")) {
            this.outputToServer.println(this.password);
        } else if (line.equals("SUBMITNAME")) {
            this.outputToServer.println(this.username);
        } else if (line.startsWith("BEAT")) {
            this.outputToServer.println("ALIVE");
        } else if (line.equals("REGISTERCHAT")) {
            this.script.registerCommand("ochat", "com.lsd.umc.nodeka.plugin.OcelChat", "chat");
            if (!tag.isEmpty()) {
                script.parse("#och TAG \"" + tag + "\"");
            }
        } else if (line.equals("UNREGISTERCHAT")) {
            this.script.unregisterCommand("ochat");
        } else if (line.equals("ROOM")) {
            this.outputToServer.println("LOCATION \"" + "HP: " + currHP + " " + this.script.getVariable("UMC_ROOM") + "\"");
            this.outputToServer.flush();
        } else if (line.startsWith("QUIT")) {
            try {
                quit(line);
            } catch (IOException ex) {
            }
        } else if (line.startsWith("ERR")) {
            this.script.captureMatch(AnsiTable.getCode("white") + "OcelChat: Error - " + line.substring(4));
        } else {
            this.script.captureMatch(line);
        }
    }

    public String healthStatus(double healthRatio) {
        String healthStatus;
        if (healthRatio > .9) {
            healthStatus = AnsiTable.getCode("white") + currHP + " " + AnsiTable.getCode("grey");
        } else if (healthRatio > .75 && healthRatio <= .9) {
            healthStatus = AnsiTable.getCode("light green") + currHP + " " + AnsiTable.getCode("grey");
        } else if (healthRatio > .5 && healthRatio <= .75) {
            healthStatus = AnsiTable.getCode("green") + currHP + " " + AnsiTable.getCode("grey");
        } else if (healthRatio > .35 && healthRatio <= 5) {
            healthStatus = AnsiTable.getCode("yellow") + currHP + " " + AnsiTable.getCode("grey");
        } else if (healthRatio > 0.2 && healthRatio <= .35) {
            healthStatus = AnsiTable.getCode("light red") + currHP + " " + AnsiTable.getCode("grey");
        } else if (healthRatio > 0.05 && healthRatio <= 0.2) {
            healthStatus = AnsiTable.getCode("red") + currHP + " " + AnsiTable.getCode("grey");
        } else if (healthRatio > 0.0 && healthRatio <= 0.05) {
            healthStatus = AnsiTable.getCode("light black") + currHP + " " + AnsiTable.getCode("grey");
        } else {
            healthStatus = AnsiTable.getCode("white") + "NA " + AnsiTable.getCode("grey");
        }
        return healthStatus;
    }

    public String chat(String msg) {

        if (!msg.endsWith("\n")) {
            msg += '\n';
        }
        try {
            if (PKFlag) {
                this.outputToServer.println(AnsiTable.getCode("light red") + "[PK] " + healthStatus(healthRatio) + msg);
            } else {
                this.outputToServer.println(healthStatus(healthRatio) + msg);
            }
            this.outputToServer.flush();
        } catch (Exception e) {
            this.script.capture(AnsiTable.getCode("light red") + "OcelChat: Error - Can't write message to server.");
        }
        return "";
    }

    public void IncomingEvent(ScriptInterface event) {
        Matcher h = health.matcher(event.getText());
        Matcher pk = pkFlag.matcher(event.getEvent());
        Matcher pvp = pvpCombat.matcher(event.getEvent());
        Matcher validPVPCombat = null;
        Matcher n = nonCombatPrompt.matcher(event.getText());

        Matcher reportHuri = huri.matcher(event.getText());
        Matcher reportContinuum = continuum.matcher(event.getText());
        //Taktikos
        Matcher reportGlacious = glacious.matcher(event.getText());
        Matcher reportHuriAffected = huriAffected.matcher(event.getText());
        Matcher reportContinuumAffected = continuumAffected.matcher(event.getText());
        Matcher reportTaktikosAffected = taktikosAffected.matcher(event.getText());
        Matcher reportWatchGlacious = watchGlacious.matcher(event.getText());

        if (h.find()) {
            currHP = Integer.parseInt(h.group(1).trim());
            if (!h.group(2).isEmpty() || h.group(2) != null) {
                maxHP = Integer.parseInt(h.group(2).trim());
            } else {
                maxHP = 0;
            }
        }

        h.reset();

        if (maxHP >= 1) {
            healthRatio = (double) currHP / (double) maxHP;
        } else {
            if (currHP > currMaxHP) {
                currMaxHP = currHP;
                healthRatio = (double) currHP / (double) currMaxHP;
            } else {
                healthRatio = (double) currHP / (double) currMaxHP;
            }
            maxHP = 0;
        }

        if (pk.find()) {
            PKFlag = true;
            script.setTimer("PKFLAG", 100);
        }

        pk.reset();

        if (client.getTimerManager().getTimerRemainingSeconds("PKFLAG") < 1) {
            script.parse("");
            if (pk.find()) {
                PKFlag = true;
                client.getTimerManager().setCommandTimer("PKFLAG", 100, "look", true);
            } else {
                PKFlag = false;
                client.getTimerManager().setCommandTimer("PKFLAG", 10, "", false);
            }
        }
        pk.reset();

        if (pvp.find()) {
            validPVPCombat = condition.matcher(pvp.group(2));
            if (validPVPCombat.find()) {
                inPVP = true;
                report = true;
                PVPTank = pvp.group(1);
                PVPOpponent = pvp.group(3);

                for (String s : blacklist) {
                    if (s.trim().equals(PVPOpponent.trim())) {
                        inPVP = false;
                        report = false;
                    }
                }

                if (report && !reported) {
                    this.outputToServer.println(healthStatus(healthRatio) + AnsiTable.getCode("light red") + "WARNING: " + AnsiTable.getCode("white") + "I am in PVP combat with: " + PVPOpponent + " at " + script.getVariable("UMC_ROOM") + "!");
                    reported = true;
                }
            }
            validPVPCombat.reset();
        }

        pvp.reset();

        if (n.find() && !pvp.find()) {
            inPVP = false;
            report = false;
            reported = false;
        }

        n.reset();

        if (reportHuri.find()) {
            this.outputToServer.println(healthStatus(healthRatio) + AnsiTable.getCode("white") + "I have" + AnsiTable.getCode("light blue") + " BATTLE LOCKED" + AnsiTable.getCode("white") + reportHuri.group(1) + "!");
        }
        if (reportHuriAffected.find()) {
            this.outputToServer.println(healthStatus(healthRatio) + AnsiTable.getCode("white") + reportHuri.group(1) + " has" + AnsiTable.getCode("light blue") + " BATTLE LOCKED " + AnsiTable.getCode("white") + "me!");
        }
        if (reportContinuum.find()) {
            this.outputToServer.println(healthStatus(healthRatio) + AnsiTable.getCode("white") + "I have" + AnsiTable.getCode("light blue") + " BATTLE LOCKED" + AnsiTable.getCode("white") + reportContinuum.group(1) + "!");
        }
        if (reportContinuumAffected.find()) {
            this.outputToServer.println(healthStatus(healthRatio) + AnsiTable.getCode("white") + reportContinuumAffected.group(1) + " has" + AnsiTable.getCode("light blue") + " BATTLE LOCKED " + AnsiTable.getCode("white") + "me!");
        }
        if (reportTaktikosAffected.find()) {
            this.outputToServer.println(healthStatus(healthRatio) + AnsiTable.getCode("white") + reportTaktikosAffected.group(1) + " has" + AnsiTable.getCode("light blue") + " BATTLE LOCKED " + AnsiTable.getCode("white") + "me!");
        }
        if (reportGlacious.find()) {
            this.outputToServer.println(healthStatus(healthRatio) + AnsiTable.getCode("white") + "I have" + AnsiTable.getCode("light blue") + " STONED " + AnsiTable.getCode("white") + reportGlacious.group(2) + "!");
        }
        if (reportWatchGlacious.find()) {
            this.outputToServer.println(healthStatus(healthRatio) + AnsiTable.getCode("white") + reportWatchGlacious.group(1) + " has" + AnsiTable.getCode("light blue") + " STONED" + AnsiTable.getCode("white") + "!");
        }
        reportHuri.reset();
        reportHuriAffected.reset();
        reportContinuum.reset();
        reportContinuumAffected.reset();
        reportTaktikosAffected.reset();
        reportGlacious.reset();
        reportWatchGlacious.reset();
    }
}

/*
 private static final Pattern huri = Pattern.compile("^Huri!! (.+) is bound by fear (locked in combat) by your rage!");
 private static final Pattern huriAffected = Pattern.compile("^Huri!! You are bound by fear (locked in combat) by (.+)'s rage!");
 private static final Pattern continuum = Pattern.compile("^You wrap a continuum of combat around (.+) - (?:.+) is combat locked!");
 private static final Pattern continuumAffected = Pattern.compile("^(.+) locks you in a continuum of combat!");
 private static final Pattern taktikosAffected = Pattern.compile("^(.+) maneuvers around you and locks you in battle\\.");
 private static final Pattern glacious = Pattern.compile("^You hold out a glacious stones in your (?:.+) and your (.+) is restored!");
 private static final Pattern watchGlacious = Pattern.compile("^(.+) holds out a glacious stones in (?:.+)\\. With a blinding light the stone vanishes\\.");
 */
//[2m[7m[2m[37m[0mL: [1;37m0[37m[0m [1;32mOcellaris[37m[0m: ([32mperfect condition[37m[0m) vs. [1;32mMadussa[37m[0m: ([1;37mperfect condition[37m[0m)
//[1;31mPKer
//You begin your huri-rage.
//Huri!! A warrior native is bound by fear (locked in combat) by your rage!
//You wrap a continuum of combat around a zealous warrior - he is combat locked!
//You begin your attempt to force a continuum of combat to rise up around a zealous warrior.
//You hold out a glacious stones in your holy hand and your endurance is restored!
//Nisei maneuvers around you and locks you in battle.
//Huri!! Nisei is bound by fear (locked in combat) by your rage!
//You wrap a continuum of combat around Nisei - he is combat locked!
//Ocellaris or Ocelot? begins attempting to lock you in a continuum of combat.
//Ocellaris or Ocelot? locks you in a continuum of combat!
//Ocellaris or Ocelot? is becoming enraged at you.
//Huri!! You are bound by fear (locked in combat) by Ocellaris or Ocelot?'s rage!
//You hold out a glacious stones in your obsidian hand and your health is restored!
//Ocellaris or Ocelot? holds out a glacious stones in his obsidian hand. With a blinding light the stone vanishes.