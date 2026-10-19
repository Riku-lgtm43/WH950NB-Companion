# Third-party notices

## Shizuku API 13.1.5

This app compiles against `dev.rikka.shizuku:api`, `aidl`, `shared`, and `provider`.
These libraries are **not committed** to this repository. The build script downloads
version 13.1.5 directly from Maven Central on the first build and packages the
required classes in the resulting APK.

- Project: https://github.com/RikkaApps/Shizuku-API
- License: MIT, Copyright (c) 2021 RikkaW
- Maven: https://central.sonatype.com/artifact/dev.rikka.shizuku/api/13.1.5

The MIT license text is reproduced below, including its original copyright notice.

```
MIT License

Copyright (c) 2021 RikkaW

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Vector icons

The project developer reports manually creating the custom vector icons in
`res/drawable/ic_*.xml`, following common contemporary interface design
conventions. This statement records the developer's account; it is not an
independent verification of the origin of every path.

The path geometry in `res/drawable/ic_expand_more.xml` matches Google's
Material `expand_more` icon. The Apache License 2.0 notice is retained
for this matching design; no claim of exclusive ownership is made for it.

- Google Material Icons: https://github.com/google/material-design-icons
- Historical matching icon path: https://gerrit.googlesource.com/gerrit/+/ed6b1a849e30c62775c34f54d4ead71cbe2da0fd/polygerrit-ui/app/elements/shared/gr-icons/gr-icons.html
- License: Apache License, Version 2.0
- License copy: `assets/licenses/Apache-2.0.txt`

See [LEGAL.md](LEGAL.md) for the project's legal notices.

## Headphone illustration

The illustrations in `assets/headphones.svg` and
`assets/headphones.png` were created by the project developer
with assistance from OpenAI's ChatGPT.

They are unofficial, unbranded illustrations and are not
presented as official Edifier artwork. AI-generated output
may not be unique, and third-party rights are not guaranteed.

## Android platform and tools

The Android SDK framework is downloaded from Google's official distribution,
not redistributed with this repository. Termux build tools are installed using
Termux package repositories, under their respective upstream licenses.

For manufacturer-name use and independent-project status, see [LEGAL.md](LEGAL.md).
