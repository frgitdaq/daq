package daq.usi.allied;

import daq.usi.BaseSwitchController;
import daq.usi.ResponseHandler;
import grpc.InterfaceResponse;
import grpc.LinkStatus;
import grpc.POENegotiation;
import grpc.POEStatus;
import grpc.POESupport;
import grpc.PowerResponse;
import grpc.SwitchActionResponse;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


public class AlliedTelesisX230 extends BaseSwitchController {
  private static final String[] powerExpected =
      {"dev_interface", "admin", "pri", "oper", "power", "device", "dev_class", "max"};
  private static final String[] showPowerExpected =
      {"Interface", "Admin", "Pri", "Oper", "Power", "Device", "Class", "Max"};
  private static final Map<String, POEStatus.State> poeStatusMap = Map.of("Powered",
      POEStatus.State.ON, "Off", POEStatus.State.OFF,
      "Fault", POEStatus.State.FAULT, "Deny", POEStatus.State.DENY);
  // TODO Not certain about AT power "Deny" status string. Can't find a device to produce that state
  private static final Map<String, POESupport.State> poeSupportMap = Map.of("Enabled",
      POESupport.State.ENABLED, "Disabled", POESupport.State.DISABLED);
  private static final Map<String, POENegotiation.State> poeNegotiationMap = Map.of("Enabled",
      POENegotiation.State.ENABLED, "Disabled", POENegotiation.State.DISABLED);
  private static final Map<Pattern, String> interfaceProcessMap =
      Map.of(Pattern.compile("Link is (\\w+)"), "link",
          Pattern.compile("current duplex (\\w+)"), "duplex",
          Pattern.compile("current speed (\\w+)"), "speed");

  private static final int WAIT_MS = 100;
  private ResponseHandler<String> responseHandler;

  /**
   * ATX230 Switch Controller.
   *
   * @param remoteIpAddress switch ip address
   * @param user            switch username
   * @param password        switch password
   */
  public AlliedTelesisX230(
      String remoteIpAddress,
      String user,
      String password) {
    this(remoteIpAddress, user, password, false);
  }

  /**
   * ATX230 Switch Controller.
   *
   * @param remoteIpAddress switch ip address
   * @param user            switch username
   * @param password        switch password
   * @param debug           for verbose output
   */
  public AlliedTelesisX230(
      String remoteIpAddress,
      String user,
      String password, boolean debug) {
    super(remoteIpAddress, user, password, debug);
    this.username = user == null ? "manager" : user;
    this.password = password == null ? "friend" : password;
    commandPending = true;
  }

  @Override
  protected void parseData(String consoleData) throws Exception {
    if (commandPending) {
      responseHandler.receiveData(consoleData);
    }
  }

  /**
   * Generic ATX230 Switch command to retrieve the Status of an interface.
   */
  private String showIfaceStatusCommand(int interfacePort) {
    return "show interface port1.0." + interfacePort;
  }

  /**
   * Generic ATX230 Switch command to retrieve the Power Status of an interface. Replace asterisk
   * with actual port number for complete message.
   */
  private String showIfacePowerStatusCommand(int interfacePort) {
    return "show power-inline interface port1.0." + interfacePort;
  }

  /**
   * Port toggle commands.
   *
   * @param interfacePort port number
   * @param enabled       for bringing up/down interfacePort
   * @return commands
   */
  private String[] portToggleCommand(int interfacePort, boolean enabled) {
    return new String[] {
        "configure terminal",
        "interface port1.0." + interfacePort,
        (enabled ? "no " : "") + "shutdown",
        "end"
    };
  }

  private String[] portSpeedCommand(int interfacePort, String speed) {
    return new String[] {
        "configure terminal",
        "interface port1.0." + interfacePort,
        "speed " + speed,
        "end"
    };
  }

  @Override
  public void getPower(int devicePort, ResponseHandler<PowerResponse> handler) throws Exception {
    while (commandPending) {
      Thread.sleep(WAIT_MS);
    }
    String command = showIfacePowerStatusCommand(devicePort);
    synchronized (this) {
      commandPending = true;
      responseHandler = data -> {
        synchronized (this) {
          commandPending = false;
        }
        Map<String, String> powerMap = processPowerStatusInline(data);
        handler.receiveData(buildPowerResponse(powerMap, data));
      };
      telnetClientSocket.writeData(command + "\n");
    }
  }

  @Override
  public void getInterface(int devicePort, ResponseHandler<InterfaceResponse> handler)
      throws Exception {
    while (commandPending) {
      Thread.sleep(WAIT_MS);
    }
    String command = showIfaceStatusCommand(devicePort);
    synchronized (this) {
      commandPending = true;
      responseHandler = data -> {
        synchronized (this) {
          commandPending = false;
        }
        Map<String, String> interfaceMap = processInterfaceStatus(data);
        handler.receiveData(buildInterfaceResponse(interfaceMap, data));
      };
      telnetClientSocket.writeData(command + "\n");
    }
  }

  private void managePort(Queue<String> commands, ResponseHandler<SwitchActionResponse> handler) throws Exception {
    while (commandPending) {
      Thread.sleep(WAIT_MS);
    }
    SwitchActionResponse.Builder response = SwitchActionResponse.newBuilder();
    synchronized (this) {
      commandPending = true;
      responseHandler = data -> {
        if (!commands.isEmpty()) {
          telnetClientSocket.writeData(commands.poll() + "\n");
          return;
        }
        synchronized (this) {
          commandPending = false;
          handler.receiveData(response.setSuccess(true).build());
        }
      };
      telnetClientSocket.writeData(commands.poll() + "\n");
    }
  }

  @Override
  public void connect(int devicePort, ResponseHandler<SwitchActionResponse> handler)
      throws Exception {
    Queue<String> commands = new LinkedList<>(Arrays.asList(portToggleCommand(devicePort, true)));
    managePort(commands, handler);
  }

  @Override
  public void disconnect(int devicePort, ResponseHandler<SwitchActionResponse> handler)
      throws Exception {
    Queue<String> commands = new LinkedList<>(Arrays.asList(portToggleCommand(devicePort, false)));
    managePort(commands, handler);
  }

  @Override
  public void setSpeed(int devicePort, int speed, ResponseHandler<SwitchActionResponse> handler) throws Exception {
    String speedString = String.valueOf(speed);
    if (speed == -1) {
      speedString = "auto";
    }
    Queue<String> commands = new LinkedList<>(Arrays.asList(portSpeedCommand(devicePort, speedString)));
    managePort(commands, handler);
  }

  private InterfaceResponse buildInterfaceResponse(Map<String, String> interfaceMap, String raw) {
    InterfaceResponse.Builder response = InterfaceResponse.newBuilder();
    if (raw != null) {
      response.setRawOutput(raw);
    }
    String duplex = interfaceMap.getOrDefault("duplex", "");
    int speed = 0;
    try {
      speed = Integer.parseInt(interfaceMap.getOrDefault("speed", ""));
    } catch (NumberFormatException e) {
      System.out.println("Could not parse int: " + interfaceMap.get("speed"));
      return response.build();
    }
    String linkStatus = interfaceMap.getOrDefault("link", "");
    return response
        .setLinkStatus(linkStatus.equals("UP") ? LinkStatus.State.UP : LinkStatus.State.DOWN)
        .setDuplex(duplex)
        .setLinkSpeed(speed)
        .build();
  }

  private PowerResponse buildPowerResponse(Map<String, String> powerMap, String raw) {
    PowerResponse.Builder response = PowerResponse.newBuilder();
    if (raw != null) {
      response.setRawOutput(raw);
    }
    float maxPower = 0;
    float currentPower = 0;
    try {
      // AT switch may add trailing "[C]" in power output.
      String maxPowerString = powerMap.getOrDefault("max", "")
          .replaceAll("\\[.*\\]", "");
      maxPower = Float.parseFloat(maxPowerString);
      currentPower = Float.parseFloat(powerMap.getOrDefault("power", ""));
    } catch (NumberFormatException e) {
      System.out.println(
          "Could not parse float: " + powerMap.get("max") + " or " + powerMap.get("power"));
    }
    String poeSupport = powerMap.getOrDefault("admin", "");
    String poeStatus = powerMap.getOrDefault("oper", "");
    return response
        .setPoeStatus(poeStatusMap.getOrDefault(poeStatus, POEStatus.State.UNKNOWN))
        .setPoeSupport(poeSupportMap.getOrDefault(poeSupport, POESupport.State.UNKNOWN))
        .setPoeNegotiation(poeNegotiationMap.getOrDefault(poeSupport, POENegotiation.State.UNKNOWN))
        .setMaxPowerConsumption(maxPower)
        .setCurrentPowerConsumption(currentPower).build();
  }

  private Map<String, String> processInterfaceStatus(String response) {
    Map<String, String> interfaceMap = new HashMap<>();
    Arrays.stream(response.split("\n")).filter(s -> !containsPrompt(s)).forEach(s -> {
      for (Pattern pattern : interfaceProcessMap.keySet()) {
        Matcher m = pattern.matcher(s);
        if (m.find()) {
          interfaceMap.put(interfaceProcessMap.get(pattern), m.group(1));
        }
      }
    });
    return interfaceMap;
  }

  private Map<String, String> processPowerStatusInline(String response) {
    String filtered = Arrays.stream(response.split("\n"))
        .filter(s -> s.trim().length() > 0
            && !s.contains("show power-inline")
            && !containsPrompt(s)
            && !s.contains("(mW)")) // AT shows mW in second line
        .collect(Collectors.joining("\n"));
    return mapSimpleTable(filtered, showPowerExpected, powerExpected);
  }

  /**
   * Handles the process when using the enter command. Enable is a required step before commands can
   * be sent to the switch.
   *
   * @param consoleData Raw console data received the the telnet connection.
   */
  public void handleEnableMessage(String consoleData) throws Exception {
    if (containsPrompt(consoleData)) {
      userEnabled = true;
      commandPending = false;
    }
  }

  /**
   * Handles the process when logging into the switch.
   *
   * @param consoleData Raw console data received the the telnet connection.
   */
  public void handleLoginMessage(String consoleData) throws Exception {
    if (consoleData.endsWith("login:")) {
      telnetClientSocket.writeData(username + "\n");
    } else if (consoleData.contains("Password:")) {
      telnetClientSocket.writeData(password + "\n");
    } else if (consoleData.contains(CONSOLE_PROMPT_ENDING_LOGIN)) {
      userAuthorised = true;
      hostname = consoleData.split(CONSOLE_PROMPT_ENDING_LOGIN)[0];
      telnetClientSocket.writeData("enable\n");
    } else if (consoleData.contains("Login incorrect")) {
      telnetClientSocket.disposeConnection();
      throw new Exception("Failed to Login, Bad Password");
    }
  }

}
