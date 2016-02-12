/*
 * The MIT License
 *
 * Copyright 2016 user.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.plugins.report.rpms;

import hudson.FilePath.FileCallable;
import hudson.remoting.VirtualChannel;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.jenkinsci.remoting.RoleChecker;

public class CommandCallable implements FileCallable<List<String>> {

    private final String command;

    public CommandCallable(String command) {
        if (command == null || command.trim().isEmpty()) {
            throw new IllegalArgumentException("No command specified");
        }
        this.command = command;
    }

    @Override
    public List<String> invoke(File f, VirtualChannel channel) throws IOException, InterruptedException {
        try {
            Process process = new ProcessBuilder(command.trim().split(" "))
                    .directory(f)
                    .start();
            OutputReader stdoutReader = new OutputReader(command, process.getInputStream()).start();
            OutputReader stderrReader = new OutputReader(command, process.getErrorStream()).start();
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                throw new OperationFailedException("Command time out");
            }
            if (stderrReader.getResult() != null) {
                throw new OperationFailedException(stderrReader.getResult());
            }
            String stdout = stdoutReader.getResult();
            if (stdout == null) {
                throw new OperationFailedException("Command produced no output");
            }
            List<String> list = StringSpliterator.splitString(stdout, "\n")
                    .filter(s -> s != null && s.length() > 0)
                    .sorted()
                    .collect(Collectors.toList());
            return list;
        } catch (OperationFailedException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new OperationFailedException(ex.toString());
        }
    }

    @Override
    public void checkRoles(RoleChecker checker) throws SecurityException {
    }

    private static class OutputReader implements Runnable {

        private final String command;
        private final InputStream stream;
        private String result;

        public OutputReader(String command, InputStream stream) {
            this.command = command;
            this.stream = stream;
        }

        public String getResult() {
            return result;
        }

        @Override
        public void run() {
            try (InputStreamReader in = new InputStreamReader(stream, "UTF-8")) {
                StringBuilder sb = new StringBuilder();
                char[] buffer = new char[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    sb.append(buffer, 0, read);
                }
                if (sb.length() > 0) {
                    result = sb.toString();
                }
            } catch (Exception ex) {
                result = ex.toString();
            }
        }

        public OutputReader start() {
            Thread t = new Thread(this);
            t.setPriority(Thread.MIN_PRIORITY);
            t.setName("RPMs Report - Output Reader - '" + command + "'");
            t.start();
            return this;
        }

    }

}
