/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.lsd.umc.nodeka.plugin;

import java.io.BufferedReader;
import java.io.IOException;

public class chat extends Thread {

    private final OcelChat ichat;
    //private final HeartBeat heartbeat;

    public chat(OcelChat ocelchat) {
        this.ichat = ocelchat;
        //this.heartbeat = heartbeat;
    }

    @Override
    public void run() {
        BufferedReader in = this.ichat.getIn();
        String line;

        // Process all messages from server, according to the protocol.
        while (true) {
            try {
                line = in.readLine();
            } catch (IOException ex) {
                break;
            }
            if (line.length() > 0) {
                this.ichat.processServerResponse(line);
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException ex) {
                break;
            }
            
        }
    }
}
