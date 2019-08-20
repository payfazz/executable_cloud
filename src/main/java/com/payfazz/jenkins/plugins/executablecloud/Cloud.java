package com.payfazz.jenkins.plugins.executablecloud;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.lang.RandomStringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import hudson.AbortException;
import hudson.Extension;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Node.Mode;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.slaves.SlaveComputer;
import hudson.util.FormValidation;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

public class Cloud extends hudson.slaves.Cloud {
    private static final Logger LOGGER = Logger.getLogger(Cloud.class.getCanonicalName());

    public final String executablePath;
    public final String label;

    @DataBoundConstructor
    public Cloud(String name, String executablePath, String label) {
        super(name);
        this.executablePath = executablePath;
        this.label = label;
    }

    @Override
    public boolean canProvision(Label paramLabel) {
        if (paramLabel == null) {
            return false;
        }
        return paramLabel.matches(Label.parse(label));
    }

    @Override
    public Collection<PlannedNode> provision(Label labelParam, int excessWorkload) {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(executablePath);
            processBuilder.environment().clear();

            Process process = processBuilder.start();
            LOGGER.log(Level.INFO, "'prepare' process created: " + process.toString());

            JSONObject prepareInput = new JSONObject();
            prepareInput.put("action", "prepare");
            prepareInput.put("num_executor", excessWorkload);

            Future<String> stderrString = Utils.stderrString(process);
            JSONObject prepareOutput = null;
            try {
                prepareOutput = Utils.comunicate(process, prepareInput).get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
            }

            if (!process.waitFor(1, TimeUnit.SECONDS)) {
                LOGGER.log(Level.INFO, "killing 'prepare' process, because of timeout: " + process.toString());
                process.destroyForcibly();
                Thread.sleep(1000);
            }

            if (process.exitValue() != 0) {
                String stderr = "";
                try {
                    stderr = stderrString.get(1, TimeUnit.SECONDS);
                } catch (Exception e) {
                }
                throw new Exception("'prepare' process exit with code=" + process.exitValue() + "\n" + stderr);
            }

            if (prepareOutput == null) {
                throw new Exception("Invalid JSONObject from 'prepare' process");
            }

            if (!prepareOutput.optString("action", "").equals("prepare")) {
                LOGGER.log(Level.WARNING, "Invalid 'action' from 'prepare' process");
                return Collections.emptyList();
            }

            JSONArray nodes = prepareOutput.optJSONArray("nodes");
            if (nodes == null) {
                LOGGER.log(Level.WARNING, "Invalid 'nodes' from 'prepare' process");
                return Collections.emptyList();
            }

            ArrayList<PlannedNode> plannedNodes = new ArrayList<>();
            for (int i = 0; i < nodes.size(); i++) {
                JSONObject nodeInfo = nodes.getJSONObject(i);

                String nodeName = name + "-" + RandomStringUtils.randomAlphanumeric(8);
                nodeInfo.put("name", nodeName);

                int numExecutor = nodeInfo.optInt("num_executor", 1);
                nodeInfo.put("num_executor", numExecutor);

                Future<Node> futureNode = Computer.threadPoolForRemoting.submit(new Callable<Node>() {
                    @Override
                    public Node call() throws Exception {
                        Slave slave = new Slave(nodeName, executablePath);
                        slave.setLabelString(label);
                        slave.setMode(Mode.EXCLUSIVE);
                        slave.setNumExecutors(numExecutor);
                        slave.setRetentionStrategy(TenMinuteRetention.get());

                        Jenkins.get().addNode(slave);
                        // wait till have computer, because addNode do not create computer immidiately
                        for (;;) {
                            SlaveComputer c = slave.getComputer();
                            if (c == null) {
                                Thread.sleep(50);
                            } else {
                                nodeInfo.put("jnlp_mac", c.getJnlpMac());
                                break;
                            }
                        }

                        try {
                            Process process = processBuilder.start();
                            LOGGER.log(Level.INFO, "'spawn' process created: " + process.toString());

                            JSONObject spawnInput = new JSONObject();
                            spawnInput.put("action", "spawn");
                            spawnInput.put("node", nodeInfo);

                            Future<String> stderrString = Utils.stderrString(process);
                            JSONObject spawnOutput = null;
                            try {
                                spawnOutput = Utils.comunicate(process, spawnInput).get(7, TimeUnit.MINUTES);
                            } catch (Exception e) {
                            }

                            if (!process.waitFor(1, TimeUnit.SECONDS)) {
                                LOGGER.log(Level.INFO,
                                        "killing 'spawn' process, because of timeout: " + process.toString());
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
                                        "'spawn' process exit with code=" + process.exitValue() + "\n" + stderr);
                            }

                            if (spawnOutput == null) {
                                throw new Exception("Invalid JSONObject from 'spawn' process");
                            }

                            if (!spawnOutput.optString("action", "").equals("spawn")) {
                                throw new Exception("Invalid 'action' from 'spawn' process");
                            }

                            if (!spawnOutput.optBoolean("spawned", false)) {
                                throw new AbortException("'spawn' process reject spaning new node");
                            }

                        } catch (Exception e) {
                            Jenkins.get().removeNode(slave);
                            LOGGER.log(Level.WARNING, e.getMessage(), e);
                            throw new AbortException();
                        }

                        // wait till online
                        for (;;) {
                            Thread.sleep(3000);
                            SlaveComputer c = slave.getComputer();
                            if (c == null) {
                                // node removed before computer got online, this is normal
                                throw new AbortException();
                            }
                            if (c.isOnline()) {
                                return slave;
                            }
                        }

                    }
                });

                plannedNodes.add(new PlannedNode(nodeName, futureNode, numExecutor));
            }

            return plannedNodes;

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failing on provision node", e);
            return Collections.emptyList();
        }
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<hudson.slaves.Cloud> {
        private static String NAME_PATTERN = "[a-z|A-Z|0-9|_|-]{1,127}";

        @Override
        public String getDisplayName() {
            return "Executable Cloud";
        }

        public FormValidation doCheckName(@QueryParameter String name) {
            if (!name.matches(NAME_PATTERN)) {
                return FormValidation.error("invalid name");
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckExecutablePath(@QueryParameter String executablePath) {
            File f = new File(executablePath);
            if (!f.canExecute()) {
                return FormValidation.error("invalid executablePath");
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckLabel(@QueryParameter String label) {
            String[] allLabel = label.split(" ");
            int count = 0;
            for (String l : allLabel) {
                if (l.length() == 0) {
                    continue;
                }
                if (!l.matches(NAME_PATTERN)) {
                    return FormValidation.error("invalid label");
                }
                count++;
            }
            if (count == 0) {
                return FormValidation.error("must have at least one label");
            }
            return FormValidation.ok();
        }
    }
}
