package com.payfazz.jenkins.plugins.executablecloud;

import java.io.IOException;

import hudson.model.Descriptor.FormException;
import hudson.slaves.JNLPLauncher;

public class Slave extends hudson.model.Slave {
    private static final long serialVersionUID = 7338857272955085354L;

    private final String executablePath;

    public Slave(String name, String executablePath) throws FormException, IOException {
        super(name, ".", new JNLPLauncher(true));
        this.executablePath = executablePath;
    }

    @Override
    public hudson.model.Computer createComputer() {
        return new Computer(this, name, executablePath);
    }
}
