java -jar /tmp/KamanjaInstall/bin/KVInit-1.0 --kvname System.GlobalPreferences  --config /tmp/KamanjaInstall/config/Engine1Config.properties --csvpath /tmp/KamanjaInstall/input/application-3/data/GlobalPreferences.dat  --keyfieldname PrefType
java -jar /tmp/KamanjaInstall/bin/KVInit-1.0 --kvname System.CustPreferences    --config /tmp/KamanjaInstall/config/Engine1Config.properties --csvpath /tmp/KamanjaInstall/input/application-3/data/CustPreferences.dat    --keyfieldname custId
java -jar /tmp/KamanjaInstall/bin/KVInit-1.0 --kvname System.CustomerInfo       --config /tmp/KamanjaInstall/config/Engine1Config.properties --csvpath /tmp/KamanjaInstall/input/application-3/data/CustomerInfo.dat       --keyfieldname custId