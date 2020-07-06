package daq.usi;

/*
 * Licensed to Google under one or more contributor license agreements.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import grpc.Interface;
import grpc.Power;
import grpc.SwitchActionResponse;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public abstract class SwitchController implements Runnable {
  /**
   * Terminal Prompt ends with '#' when enabled, '>' when not enabled.
   */
  public static final String CONSOLE_PROMPT_ENDING_ENABLED = "#";
  public static final String CONSOLE_PROMPT_ENDING_LOGIN = ">";

  // Define Common Variables Required for All Switch Interrogators
  protected SwitchTelnetClientSocket telnetClientSocket;
  protected Thread telnetClientSocketThread;
  protected String remoteIpAddress;
  protected int telnetPort;
  protected boolean debug;
  protected String username;
  protected String password;
  protected boolean userAuthorised = false;
  protected boolean userEnabled = false;
  protected String hostname = null;
  protected boolean commandPending = false;

  public SwitchController(String remoteIpAddress, int telnetPort, String username,
                          String password) {
    this(remoteIpAddress, telnetPort, username, password, false);
  }

  /**
   * Abstract Switch controller. Override this class for switch specific implementation
   *
   * @param remoteIpAddress switch ip address
   * @param telnetPort      switch telnet port
   * @param username        switch username
   * @param password        switch password
   * @param debug           for verbose logging
   */
  public SwitchController(
      String remoteIpAddress, int telnetPort, String username, String password, boolean debug) {
    this.remoteIpAddress = remoteIpAddress;
    this.telnetPort = telnetPort;
    this.username = username;
    this.password = password;
    this.debug = debug;
    telnetClientSocket =
        new SwitchTelnetClientSocket(remoteIpAddress, telnetPort, this, debug);
  }

  protected boolean containsPrompt(String consoleData) {
    // Prompts usually hostname# or hostname(config)#
    Pattern r = Pattern.compile(hostname + "\\s*(\\(.+\\))?" + CONSOLE_PROMPT_ENDING_ENABLED, 'g');
    Matcher m = r.matcher(consoleData);
    return m.find();
  }

  protected boolean promptReady(String consoleData) {
    // Prompts usually hostname# or hostname(config)#
    Pattern r = Pattern.compile(hostname + "\\s*(\\(.+\\))?" + CONSOLE_PROMPT_ENDING_ENABLED + "$");
    Matcher m = r.matcher(consoleData);
    return m.find();
  }

  /**
   * Receive the raw data packet from the telnet connection and process accordingly.
   *
   * @param consoleData Most recent data read from the telnet socket buffer
   */
  public void receiveData(String consoleData) {
    if (debug) {
      System.out.println(
          java.time.LocalTime.now() + " receivedData:\t" + consoleData);
    }
    if (consoleData != null) {
      try {
        consoleData = consoleData.trim();
        if (!userAuthorised) {
          handleLoginMessage(consoleData);
        } else if (!userEnabled) {
          handleEnableMessage(consoleData);
        } else {
          parseData(consoleData);
        }
      } catch (Exception e) {
        telnetClientSocket.disposeConnection();
        e.printStackTrace();
      }
    }
  }

  /**
   * Map a simple table containing a header and 1 row of data to a hashmap
   * This method will also attempt to correct for mis-aligned tabular data as well as empty
   * columns values.
   *
   * @param rawPacket Raw table response from a switch command
   * @param colNames  Array containing the names of the columns in the response
   * @param mapNames  Array containing names key names to map values to
   * @return A HashMap containing the values mapped to the key names provided in the mapNames array
   */
  protected static HashMap<String, String> mapSimpleTable(
      String rawPacket, String[] colNames, String[] mapNames) {
    HashMap<String, String> colMap = new HashMap<>();
    String[] lines = rawPacket.split("\n");
    if (lines.length > 0) {
      String header = lines[0].trim();
      String values = lines[1].trim();
      int lastSectionEnd = 0;
      for (int i = 0; i < colNames.length; ++i) {
        int secStart = lastSectionEnd;
        int secEnd;
        if ((i + 1) >= colNames.length) {
          // Resolving last column
          secEnd = values.length();
        } else {
          // Tabular data is not always reported in perfectly alignment, we need to calculate the
          // correct values based off of the sections in between white spaces
          int firstWhiteSpace =
              getFirstWhiteSpace(values.substring(lastSectionEnd)) + lastSectionEnd;
          int lastWhiteSpace =
              getIndexOfNonWhitespaceAfterWhitespace(values.substring(firstWhiteSpace))
                  + firstWhiteSpace;
          int nextHeaderStart = header.indexOf(colNames[i + 1]);
          secEnd = Math.min(lastWhiteSpace, nextHeaderStart);
        }
        lastSectionEnd = secEnd;
        String sectionRaw = values.substring(secStart, secEnd).trim();
        colMap.put(mapNames[i], sectionRaw);
      }
    }
    return colMap;
  }


  private static int getFirstWhiteSpace(String string) {
    char[] characters = string.toCharArray();
    for (int i = 0; i < string.length(); i++) {
      if (Character.isWhitespace(characters[i])) {
        return i;
      }
    }
    return -1;
  }

  private static int getIndexOfNonWhitespaceAfterWhitespace(String string) {
    char[] characters = string.toCharArray();
    boolean lastWhitespace = false;
    for (int i = 0; i < string.length(); i++) {
      if (Character.isWhitespace(characters[i])) {
        lastWhitespace = true;
      } else if (lastWhitespace) {
        return i;
      }
    }
    return -1;
  }

  protected abstract void parseData(String consoleData) throws Exception;

  protected abstract void handleLoginMessage(String consoleData) throws Exception;

  protected abstract void handleEnableMessage(String consoleData) throws Exception;

  public abstract void getPower(int devicePort, ResponseHandler<Power> handler) throws Exception;

  public abstract void getInterface(int devicePort, ResponseHandler<Interface> handler)
      throws Exception;

  public abstract void connect(int devicePort, ResponseHandler<SwitchActionResponse> handler)
      throws Exception;

  public abstract void disconnect(int devicePort, ResponseHandler<SwitchActionResponse> handler)
      throws Exception;

  @Override
  public void run() {
    telnetClientSocketThread = new Thread(telnetClientSocket);
    telnetClientSocketThread.start();
  }
}