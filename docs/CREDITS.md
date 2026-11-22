# Credits

## Background photographs

All photos are from Wikimedia Commons, under open licences; changes: cropped to 16:9, resized to 1920×1080,
recompressed.

**In the app** (four photos, so MyTV has a background without internet):

| Photo | Author | Licence | Original |
|---|---|---|---|
| Coast | Ansgar Koreng | [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0) | [Rubjerg Knude Fyr, Hjørring, Denmark, 1807072231, ako.jpg](https://commons.wikimedia.org/wiki/File:Rubjerg_Knude_Fyr,_Hj%C3%B8rring,_Denmark,_1807072231,_ako.jpg) |
| Fjord | Ximonic (Simo Räsänen) | [CC BY-SA 3.0](https://creativecommons.org/licenses/by-sa/3.0) | [Afternoon at Tennfjorden, Raftsundet, Hinnøya, Norway, 2015 September.jpg](https://commons.wikimedia.org/wiki/File:Afternoon_at_Tennfjorden,_Raftsundet,_Hinn%C3%B8ya,_Norway,_2015_September.jpg) |
| Windmill | Dmytro Balkhovitin | [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0) | [Overwaard Mill No.4, sunrise.jpg](https://commons.wikimedia.org/wiki/File:Overwaard_Mill_No.4,_sunrise.jpg) |
| Misty valley | Rbrechko | [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0) | [21-224-5054 NNP Synevyr RB 18.jpg](https://commons.wikimedia.org/wiki/File:21-224-5054_NNP_Synevyr_RB_18.jpg) |

**The online photo set**, which MyTV downloads and which grows over time, carries its credits with it: each photo's
author, licence and source are in [`photos/photos.json`](../photos/photos.json), and the app shows them under
Settings → System → About (select a photo).

## Screenshots

The example films and music in the screenshots are open-licence works by Blender Studio, under
[CC BY](https://creativecommons.org/licenses/by/3.0/): *Big Buck Bunny*, *Cosmos Laundromat*, *Elephants Dream* and
*Sintel* (main theme by Jan Morgenstern), with pictures from
[Wikimedia Commons](https://commons.wikimedia.org/wiki/Category:Blender_Foundation_films). The background is
"Duna en Sossusvlei, Namibia" by Diego Delso,
[CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0).

## Icons, data and code
- Weather icons: [Lucide](https://lucide.dev) (`lucide-static` 1.63.0), ISC License, rendered white to PNG.
- Weather and moon data: [Open-Meteo.com](https://open-meteo.com/), CC BY 4.0 (free tier, personal/non-commercial use).
- Weather when Open-Meteo cannot be reached: [MET Norway](https://api.met.no/) locationforecast, CC BY 4.0 (identifying User-Agent, no key).
- Offline moon calculation (fallback, next full moon): port of [SunCalc](https://github.com/mourner/suncalc) by Vladimir Agafonkin, BSD-2-Clause.
- Ideas studied in other TV home screens (no code copied): [tvOS-Launcher](https://github.com/techgenius1/tvOS-Launcher)
  (Apache-2.0) and [tv-vibehack](https://github.com/Gathin23/tv-vibehack).
- Approximate location for the weather (only city level, on the welcome screen and weather settings): [ipapi.co](https://ipapi.co).
- QR code for adding own photos: [ZXing core](https://github.com/zxing/zxing) 3.5.4, Apache-2.0.
- Android toolchain: AGP 9.5.1, Gradle 9.9.1, Jetpack Compose BOM 2026.09.00, AndroidX core, lifecycle, activity, exifinterface and profileinstaller (Apache-2.0).
