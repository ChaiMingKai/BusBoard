# Bus Board

Bus Board is an Android 8.0+ Singapore bus arrivals app with a native home-screen widget. Open the app, select a stop and service, then tap **Add widget**. Save any number of services in the app. Choose up to eight under **Widget services** for your home screen. Existing saved services carry over when updating from 1.0. Search by bus number, stop code, road, or block numbers such as 523/525. Use the menu on a saved service to move, change, or remove it.

Each row shows the next three available predictions and the vehicle type supplied for each arrival. The widget shows how long ago its displayed minutes were calculated. The bus icon indicates vehicle type. A seat indicates seats available, one standing passenger indicates standing room, and two passengers indicate limited standing room. A question mark means the feed has no crowding report. Tap the circular arrow for a fresh estimate. The home board refreshes arrivals every 30 seconds while it is open. Missing predictions appear as dashes.

Tap **Live** to enable live widget updates. In the app, tap **Live refresh** under **Home screen widget** and enter any whole number of seconds. The default is 30 seconds, and values are clamped to 1–60 seconds. Changing the interval restarts Live if it is on. With Live off, Android schedules background widget refreshes approximately every 30 minutes. Android may defer both schedules.

Tap **Browse stops** from the home page and use **Find a stop** to search the stop directory. The stop page shows all services at the selected stop with up to three predictions per service. Arrivals refresh every 30 seconds while the page is open.

Bus Board checks for a new release when the home page comes to the foreground, at most once per hour automatically. When a new version is available, an in-app dialog shows the version and offers **Update** to open the release page or **Later** to dismiss it. Tap **Check for updates** on the home page to check immediately and see the result, including when there are no updates or the check fails.

## Build

Open this folder in Android Studio with JDK 17 and Android SDK Platform 35, or run `./gradlew assembleDebug`. The APK is generated under `app/build/outputs/apk/debug/`.

## Transit data

Arrival estimates are retrieved over HTTPS from ArriveLah, a public proxy for Singapore LTA DataMall. The bundled stop and route directory is derived from BusRouter SG data. A request sends the selected five-digit stop code. Saved slots and the latest response are stored locally.

- https://github.com/cheeaun/arrivelah
- https://data.busrouter.sg/v1/stops.min.json
- https://data.busrouter.sg/v1/services.min.json
- https://datamall.lta.gov.sg/
