package com.payfazz.jenkins.plugins.executablecloud;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;

import net.sf.json.JSONObject;

class Utils {
    static Future<String> stderrString(Process process) {
        return Computer.threadPoolForRemoting.submit(new Callable<String>() {
            @Override
            public String call() throws Exception {
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
                StringBuilder builder = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (builder.length() < 2048) {
                        builder.append(line);
                    }
                }
                return builder.toString();
            }
        });
    }

    static Future<JSONObject> comunicate(Process process, JSONObject data) {
        return Computer.threadPoolForRemoting.submit(new Callable<JSONObject>() {
            @Override
            public JSONObject call() throws Exception {
                OutputStream output = process.getOutputStream();
                PrintWriter writer = new PrintWriter(output);
                data.write(writer);
                writer.println();
                writer.flush();
                writer.close();
                output.close();

                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                StringBuilder builder = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    builder.append(line);
                    try {
                        return JSONObject.fromObject(builder.toString());
                    } catch (Exception e) {
                    }
                }
                return null;
            }
        });
    }
}
