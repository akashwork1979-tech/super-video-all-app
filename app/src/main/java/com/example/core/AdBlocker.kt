package com.example.core

import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lightweight, zero-overhead host and URL-pattern ad & tracker blocker
 * plus smart DOM mutation script provider for embedded WebViews.
 */
object AdBlocker {

    private val blockedCounter = AtomicInteger(0)

    val blockedCount: Int
        get() = blockedCounter.get()

    fun resetSessionCount() {
        blockedCounter.set(0)
    }

    private val blockedHosts: Set<String> = hashSetOf(
        "doubleclick.net",
        "googleadservices.com",
        "googlesyndication.com",
        "adservice.google.com",
        "pagead2.googlesyndication.com",
        "tpc.googlesyndication.com",
        "ade.googlesyndication.com",
        "adclick.g.doubleclick.net",
        "pubads.g.doubleclick.net",
        "securepubads.g.doubleclick.net",
        "google-analytics.com",
        "ssl.google-analytics.com",
        "analytics.google.com",
        "googletagmanager.com",
        "googletagservices.com",
        "ads.youtube.com",
        "s.youtube.com",
        "an.facebook.com",
        "pixel.facebook.com",
        "ads.twitter.com",
        "ads-api.twitter.com",
        "analytics.twitter.com",
        "static.ads-twitter.com",
        "ads.x.com",
        "events.redditmedia.com",
        "alb.reddit.com",
        "d.reddit.com",
        "ad.doubleclick.net",
        "adnxs.com",
        "adsrvr.org",
        "amazon-adsystem.com",
        "criteo.com",
        "criteo.net",
        "outbrain.com",
        "taboola.com",
        "scorecardresearch.com",
        "quantserve.com",
        "moatads.com",
        "rubiconproject.com",
        "pubmatic.com",
        "openx.net",
        "casalemedia.com",
        "chartbeat.com",
        "branch.io",
        "app.link",
        "adjust.com",
        "appsflyer.com",
        "adcolony.com",
        "applovin.com",
        "unityads.unity3d.com",
        "vungle.com",
        "flurry.com",
        "hotjar.com",
        "mixpanel.com",
        "segment.io",
        "promoted.soundcloud.com"
    )

    private val blockedPathPatterns: List<String> = listOf(
        "/pagead/",
        "/ptracking",
        "/api/stats/ads",
        "/api/stats/atr",
        "/youtubei/v1/log_event",
        "/get_midroll_info",
        "/pcs/activeview",
        "/ad_status",
        "/doubleclick/",
        "/sponsored_post",
        "adformat=",
        "ad_type=",
        "adunit="
    )

    fun isAdRequest(uri: Uri?): Boolean {
        if (uri == null) return false
        val host = uri.host?.lowercase() ?: return false
        val url = uri.toString().lowercase()

        // Match exact host or subdomain suffix
        if (blockedHosts.contains(host)) {
            blockedCounter.incrementAndGet()
            return true
        }
        var dotIdx = host.indexOf('.')
        while (dotIdx != -1) {
            val parent = host.substring(dotIdx + 1)
            if (blockedHosts.contains(parent)) {
                blockedCounter.incrementAndGet()
                return true
            }
            dotIdx = host.indexOf('.', dotIdx + 1)
        }

        for (pattern in blockedPathPatterns) {
            if (url.contains(pattern)) {
                blockedCounter.incrementAndGet()
                return true
            }
        }
        return false
    }

    fun createEmptyResponse(): WebResourceResponse =
        WebResourceResponse(
            "text/plain",
            "utf-8",
            204,
            "No Content",
            mapOf("Cache-Control" to "no-store"),
            ByteArrayInputStream(ByteArray(0))
        )

    /**
     * Unified smart script injected into WebViews:
     * 1) Overrides Page Visibility API (document.hidden = false, visibilityState = 'visible')
     *    so background playback never auto-pauses when minimized or screen locked.
     * 2) Installs a lightweight MutationObserver to dismiss "Open in App" prompts,
     *    sponsored overlays, and skippable ad prompts.
     * 3) Bridges HTML5 <video>/<audio> events (play, pause, ended, title) to OmniBridge.
     */
    fun buildCoreInjectionScript(
        adBlockEnabled: Boolean,
        backgroundPlayEnabled: Boolean,
        audioOnlyEnabled: Boolean,
        playbackSpeed: Float
    ): String {
        val safeSpeed = playbackSpeed.coerceIn(0.25f, 3.0f)
        return """
            (function() {
              try {
                // 1. Background Play Visibility Lock
                if (${backgroundPlayEnabled} && !window.__omniVisPatched) {
                  window.__omniVisPatched = true;
                  Object.defineProperty(document, 'hidden', { get: function() { return false; }, configurable: true });
                  Object.defineProperty(document, 'webkitHidden', { get: function() { return false; }, configurable: true });
                  Object.defineProperty(document, 'visibilityState', { get: function() { return 'visible'; }, configurable: true });
                  Object.defineProperty(document, 'webkitVisibilityState', { get: function() { return 'visible'; }, configurable: true });
                  window.addEventListener('visibilitychange', function(e) { e.stopImmediatePropagation(); }, true);
                  document.addEventListener('visibilitychange', function(e) { e.stopImmediatePropagation(); }, true);
                  window.addEventListener('webkitvisibilitychange', function(e) { e.stopImmediatePropagation(); }, true);
                  window.addEventListener('pagehide', function(e) { e.stopImmediatePropagation(); }, true);
                  window.addEventListener('blur', function(e) { e.stopImmediatePropagation(); }, true);
                }

                // 2. Apply Playback Speed & Audio-Only Mode
                window.__omniSpeed = ${safeSpeed};
                window.__omniAudioOnly = ${audioOnlyEnabled};
                window.__omniApplyMediaPrefs = function() {
                  var nodes = document.querySelectorAll('video, audio');
                  for (var i = 0; i < nodes.length; i++) {
                    var el = nodes[i];
                    if (el.playbackRate !== window.__omniSpeed) {
                      try { el.playbackRate = window.__omniSpeed; } catch(_) {}
                    }
                    if (el.tagName === 'VIDEO') {
                      if (window.__omniAudioOnly) {
                        el.style.opacity = '0.01';
                        el.style.visibility = 'hidden';
                      } else {
                        el.style.opacity = '';
                        el.style.visibility = '';
                      }
                    }
                    if (!el.__omniHooked) {
                      el.__omniHooked = true;
                      el.addEventListener('play', function() {
                        try { el.playbackRate = window.__omniSpeed; } catch(_) {}
                        if (window.OmniBridge) {
                          window.OmniBridge.onMediaStateChanged(true, document.title || location.hostname, location.href);
                        }
                      });
                      el.addEventListener('pause', function() {
                        if (window.OmniBridge) {
                          window.OmniBridge.onMediaStateChanged(false, document.title || location.hostname, location.href);
                        }
                      });
                      el.addEventListener('ended', function() {
                        if (window.OmniBridge) {
                          window.OmniBridge.onMediaEnded(location.href);
                        }
                      });
                    }
                  }
                };
                window.__omniApplyMediaPrefs();

                // 3. Ad & Open-in-App Overlay Cleaner
                if (${adBlockEnabled} && !window.__omniAdObserver) {
                  window.__omniAdObserver = true;
                  var hideSelectors = [
                    '.ytp-ad-module',
                    '.ytp-ad-overlay-container',
                    '.ytp-ad-player-overlay',
                    'ytm-promoted-sparkles-web-renderer',
                    'ytm-companion-ad-renderer',
                    'ytd-promoted-video-renderer',
                    'ytd-ad-slot-renderer',
                    'ytm-app-banner-renderer',
                    '[data-testid="app-install-banner"]',
                    '[aria-label*="Open in app" i]',
                    'shreddit-experience-tree',
                    'xpromo-app-selector',
                    '.open-in-app-banner',
                    '.upsell-banner',
                    'iframe[src*="doubleclick"]',
                    'iframe[src*="googlesyndication"]'
                  ];
                  var cleanDom = function() {
                    try {
                      var skipBtns = document.querySelectorAll('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button');
                      for (var s = 0; s < skipBtns.length; s++) {
                        skipBtns[s].click();
                      }
                      for (var k = 0; k < hideSelectors.length; k++) {
                        var matches = document.querySelectorAll(hideSelectors[k]);
                        for (var m = 0; m < matches.length; m++) {
                          matches[m].style.display = 'none';
                        }
                      }
                      window.__omniApplyMediaPrefs();
                    } catch(_) {}
                  };
                  cleanDom();
                  var observer = new MutationObserver(cleanDom);
                  if (document.body) {
                    observer.observe(document.body, { childList: true, subtree: true });
                  }
                }
              } catch (err) {}
            })();
        """.trimIndent()
    }

    fun buildPlaybackSpeedScript(speed: Float): String {
        val safeSpeed = speed.coerceIn(0.25f, 3.0f)
        return """
            (function() {
              window.__omniSpeed = $safeSpeed;
              document.querySelectorAll('video, audio').forEach(function(el) {
                try { el.playbackRate = $safeSpeed; } catch(_) {}
              });
            })();
        """.trimIndent()
    }

    fun buildAudioOnlyScript(enabled: Boolean): String = """
        (function() {
          window.__omniAudioOnly = $enabled;
          document.querySelectorAll('video').forEach(function(el) {
            if ($enabled) {
              el.style.opacity = '0.01';
              el.style.visibility = 'hidden';
            } else {
              el.style.opacity = '';
              el.style.visibility = '';
            }
          });
        })();
    """.trimIndent()

    fun buildPlayPauseScript(shouldPlay: Boolean): String = """
        (function() {
          var media = document.querySelector('video, audio');
          if (media) {
            if ($shouldPlay) { media.play().catch(function(){}); }
            else { media.pause(); }
          }
        })();
    """.trimIndent()

    fun buildSeekDeltaScript(deltaSeconds: Int): String = """
        (function() {
          var media = document.querySelector('video, audio');
          if (media && !isNaN(media.currentTime)) {
            var next = Math.max(0, media.currentTime + ($deltaSeconds));
            if (!isNaN(media.duration) && media.duration > 0) {
              next = Math.min(media.duration, next);
            }
            media.currentTime = next;
          }
        })();
    """.trimIndent()

    fun buildLoopScript(loopSingle: Boolean): String = """
        (function() {
          document.querySelectorAll('video, audio').forEach(function(el) {
            el.loop = $loopSingle;
          });
        })();
    """.trimIndent()
}
