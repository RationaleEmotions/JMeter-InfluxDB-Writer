package rocks.nt.apm.jmeter.internal;

public final class RuntimeBehavior {

  private RuntimeBehavior() {}

  public static boolean skipRunningListener() {
    return Boolean.getBoolean("jmeter.influxdb.disable");
  }

}
