package com.payfazz.jenkins.plugins.executablecloud;

import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.sf.json.JSONObject;

public class Computer extends hudson.slaves.SlaveComputer {
    private static final Logger LOGGER = Logger.getLogger(Computer.class.getCanonicalName());

    private final String nodeName;
    private final String executablePath;

    public Computer(hudson.model.Slave slave, String nodeName, String executablePath) {
        super(slave);
        this.nodeName = nodeName;
        this.executablePath = executablePath;
    }

    @Override
    protected void onRemoved() {
        super.onRemoved();
        Computer.threadPoolForRemoting.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    ProcessBuilder processBuilder = new ProcessBuilder(executablePath);
                    processBuilder.environment().clear();

                    Process process = processBuilder.start();
                    LOGGER.log(Level.INFO, "'remove_hint' process created: " + process.toString());

                    JSONObject removeHintInput = new JSONObject();
                    removeHintInput.put("action", "remove_hint");
                    removeHintInput.put("node_name", nodeName);

                    Future<String> stderrString = Utils.stderrString(process);
                    try {
                        Utils.comunicate(process, removeHintInput).get(3, TimeUnit.MINUTES);
                    } catch (Exception e) {
                    }

                    if (!process.waitFor(1, TimeUnit.SECONDS)) {
                        LOGGER.log(Level.INFO,
                                "killing 'remove_hint' process, because of timeout: " + process.toString());
                        process.destroyForcibly();
                        Thread.sleep(1000);
                    }

                    if (process.exitValue() != 0) {
                        String stderr = "";
                        try {
                            stderr = stderrString.get(1, TimeUnit.SECONDS);
                        } catch (Exception e) {
                        }
                        throw new Exception(
                                "'remove_hint' process exit with code=" + process.exitValue() + "\n" + stderr);
                    }

                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Failing on removing computer", e);
                }
            }
        });
    }
}