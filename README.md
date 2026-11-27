# Bus Board

Bus Board is an Android 8.0+ Singapore bus arrivals app with a native home-screen widget. Open the app, select a stop and service, then tap **Add widget**. Save any number of services in the app. Choose up to four under **Widget services** for your home screen. Existing saved services carry over when updating from 1.0. Search by bus number, stop code, road, or block numbers such as 523/525. Use the menu on a saved service to move, change, or remove it.

Each row shows the next three available predictions and the vehicle type supplied for each arrival. The widget shows how long ago its displayed minutes were calculated. Under each time, the bus icon indicates vehicle type. A seat indicates seats available, one standing passenger indicates standing room, and two passengers indicate limited standing room. A question mark means the feed has no crowding report. Tap the circular arrow for a fresh estimate. Android schedules background widget refreshes approximately every 30 minutes and may defer them. The open app checks every 30 seconds. Missing predictions appear as dashes.

## Build

Open this folder in Android Studio with JDK 17 and Android SDK Platform 35, or run `./gradlew assembleDebug`. The APK is generated under `app/build/outputs/apk/debug/`.

## Transit data

Arrival estimates are retrieved over HTTPS from ArriveLah, a public proxy for Singapore LTA DataMall. The bundled stop and route directory is derived from BusRouter SG data. A request sends the selected five-digit stop code. Saved slots and the latest response are stored locally.

- https://github.com/cheeaun/arrivelah
- https://data.busrouter.sg/v1/stops.min.json
- https://data.busrouter.sg/v1/services.min.json
- https://datamall.lta.gov.sg/
