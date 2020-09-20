# JMeter-InfluxDB-Writer
Plugin for JMeter that allows to write load test data on-the-fly to influxDB.

For an end to end explanation of how to go about doing the entire setup refer [here](https://sfakrudeen78.github.io/JMeter-InfluxDB-Writer/).

This is a forked repository.

For now the only new addition that was done to this repository was to add a new JVM argument which when set, this plugin can be disabled.

`-Djmeter.influxdb.disable`

The modified uber jar can be downloaded from [Maven central](https://repo1.maven.org/maven2/com/rationaleemotions/jmeter/plugins/influxdb-writer/1.4.0/influxdb-writer-1.4.0.jar)

The Maven GAV co-ordinates are as below:

```xml
<dependency>
    <groupId>com.rationaleemotions.jmeter.plugins</groupId>
    <artifactId>influxdb-writer</artifactId>
    <version>1.4.0</version>
</dependency>

```
