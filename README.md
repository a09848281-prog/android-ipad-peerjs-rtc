# A22 Camera Link

Galaxy A22（Android 13）からiPadへ、カメラ・マイクをWebRTCで送信する個人用試作です。

## 構成

- Android：Java製ネイティブアプリ（Kotlin不使用）
- iPad：GitHub Pagesで配信するPWA
- シグナリング：Google Apps Script（GAS）。接続情報のみを一時交換
- メディア：WebRTCで端末間を直接接続（ネットワーク条件によりTURN中継が必要）
- APKビルド：GitHub Actions

映像・音声そのものをGASやGitHubへ保存・中継する設計ではありません。

## 使い方

1. GASの `gas/Code.gs` をGoogle Apps Scriptへ貼り付けます。
2. GASをウェブアプリとしてデプロイします。実行ユーザーは自分、アクセス権は利用環境に合う設定にします。URL末尾が `/exec` のURLを控えます。
3. GitHub Pagesで公開されたiPad用ページを開きます： https://a09848281-prog.github.io/android-ipad-peerjs-rtc/
4. iPadでGASの/exec URLと12桁の接続コードを入力します。Android側にも同じ値を入力します。
5. Android APKをインストールし、アプリを開きます。
6. AndroidでGAS URLと接続コードを入力し、「配信開始」を押します。カメラ・マイク・通知の権限を許可します。
7. iPadで「Androidからの映像を受信」を押します。
8. 接続が終わったらAndroid通知の「配信停止」を押します。iPad側からも「切断」できます。

## APKの取得

1. GitHubの Actions を開きます。
2. 最新の「Android APK Build」ワークフローを選びます。
3. Build が成功した実行を開きます。
4. Artifacts の `A22-Camera-Link-debug-apk` をダウンロードしてZIPを展開します。
5. 中の `app-debug.apk` をAndroidへ移してインストールします。

Actionsページ： https://github.com/a09848281-prog/android-ipad-peerjs-rtc/actions/workflows/android-build.yml

## バックグラウンド・ロック画面

Androidはフォアグラウンドサービス（カメラ・マイク種別）として配信し、常時通知と部分的WakeLockを使用します。ユーザーがアプリ画面で配信開始を押した後は、画面ロック中も動作継続を試みます。

ただし、Androidのプライバシー制御、カメラの占有状態、Samsungの省電力制御、OSによるプロセス終了、通信断などにより停止する場合があります。ロック画面から遠隔で新しくカメラ・マイクを起動する機能ではありません。端末でカメラ・マイクのプライバシー表示と配信通知を確認してください。

## クラッシュ・安定性について

- Javaのみで実装し、Kotlinは使用していません。
- カメラ・マイク・通知の権限を開始前に確認します。
- WebRTC初期化・接続・停止の処理をワーカースレッドへ分離します。
- SDP応答中にサービスが停止した場合の古い接続参照を確認します。
- サービス終了時はカメラ、音声、WebRTC、WakeLockを順番に解放します。
- 通知から明示的に配信停止できます。

これらはコード上の対策です。Galaxy A22実機でのクラッシュ再現試験・長時間ロック画面試験はまだ完了していません。ビルド成功と実機での安定動作は別なので、APKを作成した後に実機確認が必要です。

## 接続の制限

- STUNサーバーのみで接続できないネットワークではTURNサーバーが必要です。現時点でTURN資格情報は設定していません。
- 接続コードは第三者に共有しないでください。GASの公開範囲とキャッシュ有効期間にも注意してください。
- GASのキャッシュを使う一時シグナリング方式です。大量利用や商用利用向けではありません。
- iPadのSafariでは初回に音声再生ボタンを押す必要がある場合があります。
