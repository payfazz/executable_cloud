package com.payfazz.jenkins.plugins.executablecloud;

import java.io.IOException;

import javax.annotation.concurrent.GuardedBy;

import hudson.model.Node;
import hudson.slaves.RetentionStrategy;
import jenkins.model.Jenkins;

class TenMinuteRetention extends RetentionStrategy<Computer> {
    private static TenMinuteRetention instance;

    public static TenMinuteRetention get() {
        if (instance == null) {
            instance = new TenMinuteRetention();
        }
        return instance;
    }

    @Override
    @GuardedBy("hudson.model.Queue.lock")
    public long check(Computer c) {
        long now = System.currentTimeMillis();
        long idleStart = c.getIdleStartMilliseconds();
        if (idleStart < 0) { // to avoid overflow when calculate elapsed
            idleStart = 0;
        }
        long elapsed = now - idleStart;
        // something must be wrong if elapsed bigger than 1 day, do not remove that node
        if (600000 /* 10 minute */ < elapsed && elapsed < 86400000 /* 1 day */) {
            try {
                Node n = c.getNode();
                if (n != null) {
                    Jenkins.get().removeNode(n);
                }
            } catch (IOException e) {
            }
        }
        return 1 /* 1 minute */;
    }
}
