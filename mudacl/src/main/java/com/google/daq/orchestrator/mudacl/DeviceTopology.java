package com.google.daq.orchestrator.mudacl;

import java.util.Map;
import java.util.TreeMap;

public class DeviceTopology {

  private static final String DP_PORT_FORMAT = "dp_%s_port_%d";
  private static final String TEMPLATE_FORMAT = "template_%s";

  public Map<MacIdentifier, Placement> macAddrs = new TreeMap<>();

  static class MacIdentifier extends StringId {
    public MacIdentifier(String macAddr) {
      super(macAddr);
    }

    public static MacIdentifier fromString(String macAddr) {
      return new MacIdentifier(macAddr);
    }
  }

  static class Placement {
    public String dpName;
    public Integer portNum;

    public String toString() {
      return isTemplate()
          ? String.format(TEMPLATE_FORMAT, dpName)
          : String.format(DP_PORT_FORMAT, dpName, portNum);
    }

    boolean isTemplate() {
      return portNum == null;
    }
  }
}
