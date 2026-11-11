# A22 Camera Link

iPad PWAで、Android 13搭載 Galaxy A22 のカメラ・マイク映像を受信する個人用試作です。

## 構成

- iPad：GitHub Pagesで公開するPWA（docs/）
- Android：Java製ネイティブAPK（android/）
- シグナリング：Google Apps Script（gas/Code.gs）
- メディア通信：WebRTC。GASへ映像・音声は送信しません。
- Android WebRTC：io.github.webrtc-sdk:android

PeerJSはJavaScript向けライブラリのため、ネイティブAndroidへ直接組み込まず、Android側はネイティブWebRTCを使用します。iPadの標準WebRTC APIと相互接続します。

## 重要な動作制限

1. Androidでアプリを開き、カメラ・マイク権限を許可して「配信開始」を押す必要があります。
2. 開始後はカメラ・マイクのフォアグラウンドサービスと常時通知を使用します。通知からいつでも停止できます。
3. すでに開始した配信が画面ロック後も続くことを想定していますが、Samsungの省電力設定、OSによるプロセス終了、カメラ制御、通信状況で停止する可能性があります。A22実機での確認が必要です。
4. ロック画面から遠隔で新しくカメラ・マイクを起動すること、ユーザー操作・許可・通知を回避することはできません。
5. STUNだけでは接続できないNAT環境があります。その場合は利用契約のあるTURNサーバーが必要です。TURN設定欄はiPad PWAにあります。利用するTURN事業者の認証情報を入力してください。
6. この試作は接続コードを知る相手がシグナリング情報へアクセスできる設計です。12桁コードを公開・共有しないでください。CacheServiceのシグナリング情報は一時保存で、最大30分を目安に失効します。
7. このコードは実機テスト済みを意味しません。GitHub ActionsのAPKビルド結果と、A22での実際のカメラ・マイク・画面ロック試験を分けて確認してください。

## 1. GASを設定

1. Google Apps Scriptで新しいプロジェクトを作成します。
2. gas/Code.gs の内容を貼り付けて保存します。
3. 「デプロイ」→「新しいデプロイ」→「ウェブアプリ」を選択します。
4. 実行ユーザーは自分、アクセスできるユーザーは「全員」に設定してデプロイします。
5. 発行された /exec URLをiPadとAndroidの両方へ入力します。

シグナリングにはGoogle Apps ScriptのCacheServiceを使います。映像・音声の保存や中継は行いません。

## 2. GitHub Pagesを有効化

1. GitHubリポジトリの Settings → Pages を開きます。
2. Build and deployment の Sourceを「GitHub Actions」にします。
3. mainブランチへdocs/の変更が入るとPagesワークフローが公開します。
4. 公開URLは https://a09848281-prog.github.io/android-ipad-peerjs-rtc/ です。

## 3. Android APKを取得

1. GitHubの Actions タブを開きます。
2. 「Android APK Build」の最新実行を選びます。
3. 成功した実行の Artifacts から A22-Camera-Link-debug-apk をダウンロードします。
4. ZIPを展開し、app-debug.apkをA22へ移してインストールします。
5. 初回起動でカメラ・マイク・通知を許可します。

## 4. 接続

1. iPadのPWAを開き、GAS URLを入力します。
2. 「新しいコードを作る」を押し、12桁の番号を控えます。
3. Androidアプリへ同じGAS URLと12桁コードを入力します。
4. Androidで「配信開始」を押し、通知が出たことを確認します。
5. iPadで「接続して受信」を押します。
6. 接続後、必要なら「音声を再生」を押します。

## 5. ビルド仕様

- Android Gradle Plugin: 8.7.3
- Gradle: 8.9
- JDK: 17
- compileSdk / targetSdk: 35
- minSdk: 23
- Androidコード: Java

## トラブル時

- 「ICE接続失敗」：TURNサーバーが必要なネットワークの可能性があります。
- 黒画面：Android通知の状態、カメラ権限、カメラエラー、A22の画面ロック後のカメラ継続可否を確認してください。
- 音が出ない：iPadの消音設定、音量、自動再生の制限、「音声を再生」ボタンを確認してください。
- GAS応答エラー：/exec URL、デプロイ時のアクセス権限、再デプロイ後のURLを確認してください。
- 配信が止まる：Samsungのバッテリー設定でこのアプリのバックグラウンド動作を制限しないようにします。ただし省電力制限を解除しても継続を保証できません。
