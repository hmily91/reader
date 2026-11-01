/* eslint-disable no-console */

import { register } from "register-service-worker";

export function registerServiceWorker() {
  try {
    if (
      process.env.NODE_ENV === "production" &&
      !window.getQueryString("nopwa")
    ) {
      register(`${process.env.BASE_URL}service-worker.js`, {
        ready() {
          // console.log(
          //   "App is being served from cache by a service worker.\n" +
          //     "For more details, visit https://goo.gl/AFskqB"
          // );
          window.serviceWorkerReady = true;
        },
        registered(registration) {
          // console.log("Service worker has been registered.");
          if (window.localStorage) {
            const currentVersion = window.localStorage.getItem(
              "READER_APP_BUILD_VERSION"
            );
            const newVersion = process.env.VUE_APP_BUILD_VERSION;
            if (currentVersion !== newVersion) {
              registration.active && registration.active.postMessage({ type: "SKIP_WAITING" });
              window.localStorage.setItem(
                "READER_APP_BUILD_VERSION",
                newVersion
              );
            }
          }
        },
        // 检测到新版本：跳过等待并自动刷新一次，
        // 否则用户会一直停留在 Service Worker 缓存的旧页面上（Ctrl+F5 也绕不过 SW）
        updated(registration) {
          try {
            const newVersion = process.env.VUE_APP_BUILD_VERSION;
            const reloadKey = "READER_APP_RELOADED_VERSION";
            if (!window.localStorage) return;
            if (window.localStorage.getItem(reloadKey) === newVersion) return;
            window.localStorage.setItem(reloadKey, newVersion);
            const worker = registration && (registration.waiting || registration.installing);
            if (worker) {
              worker.postMessage({ type: "SKIP_WAITING" });
            }
            setTimeout(function () {
              window.location.reload();
            }, 1500);
          } catch (error) {
            //
          }
        }
        // cached() {
        //   console.log("Content has been cached for offline use.");
        // },
        // updatefound() {
        //   console.log("New content is downloading.");
        // },
        // updated() {
        //   console.log("New content is available; please refresh.");
        // },
        // offline() {
        //   console.log(
        //     "No internet connection found. App is running in offline mode."
        //   );
        // },
        // error(error) {
        //   console.error("Error during service worker registration:", error);
        // }
      });
    }
  } catch (error) {
    //
  }
}
