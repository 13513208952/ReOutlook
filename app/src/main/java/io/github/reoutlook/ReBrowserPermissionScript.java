package io.github.reoutlook;

/** Fixed semantic website API policy installed before untrusted page scripts. */
final class ReBrowserPermissionScript {
    private ReBrowserPermissionScript() {}

    static final String DOCUMENT_START = """
            (() => {
              if (globalThis.__reBrowserPermissionPolicyInstalled) return;
              globalThis.__reBrowserPermissionPolicyInstalled = true;
              const bridge = globalThis.reBrowserPermissionBridge;
              const denied = message => new DOMException(message || 'Permission denied', 'NotAllowedError');
              const pending = new Map();
              let sequence = 0;
              const send = (operation, value = {}) => new Promise((resolve, reject) => {
                if (!bridge || typeof bridge.postMessage !== 'function') {
                  reject(denied('Permission bridge unavailable'));
                  return;
                }
                const id = String(++sequence);
                pending.set(id, {resolve, reject});
                try {
                  bridge.postMessage(JSON.stringify({id, operation, ...value}));
                } catch (_) {
                  pending.delete(id);
                  reject(denied('Permission request failed'));
                }
              });
              if (bridge) bridge.onmessage = event => {
                try {
                  const value = JSON.parse(String(event.data || '{}'));
                  const callback = pending.get(String(value.id || ''));
                  if (!callback) return;
                  pending.delete(String(value.id));
                  if (value.ok) callback.resolve(value.value);
                  else callback.reject(denied(String(value.message || 'Permission denied')));
                } catch (_) {}
              };

              const requireClipboardGesture = () => {
                if (navigator.userActivation && !navigator.userActivation.isActive) {
                  return Promise.reject(denied('Clipboard requires a user gesture'));
                }
                return null;
              };
              const clipboard = Object.freeze({
                readText: () => requireClipboardGesture() ||
                  send('clipboard-read').then(value => String(value || '')),
                writeText: value => requireClipboardGesture() ||
                  send('clipboard-write', {text: String(value ?? '')}).then(() => undefined),
                read: () => Promise.reject(new DOMException('Rich clipboard is unsupported', 'NotSupportedError')),
                write: () => Promise.reject(new DOMException('Rich clipboard is unsupported', 'NotSupportedError'))
              });
              try {
                const navigatorPrototype = Object.getPrototypeOf(navigator);
                Object.defineProperty(navigatorPrototype, 'clipboard', {
                  get: () => clipboard, configurable: false, enumerable: true
                });
                Object.defineProperty(navigator, 'clipboard', {
                  value: clipboard, configurable: false, enumerable: true, writable: false
                });
              } catch (_) {}

              const position = value => ({
                coords: {
                  latitude: Number(value.latitude), longitude: Number(value.longitude),
                  accuracy: Number(value.accuracy), altitude: value.altitude == null ? null : Number(value.altitude),
                  altitudeAccuracy: value.altitudeAccuracy == null ? null : Number(value.altitudeAccuracy),
                  heading: value.heading == null ? null : Number(value.heading),
                  speed: value.speed == null ? null : Number(value.speed)
                },
                timestamp: Number(value.timestamp || Date.now())
              });
              const locationRequest = options => send('location', {
                highAccuracy: Boolean(options && options.enableHighAccuracy),
                timeout: Math.max(1000, Math.min(30000, Number(options && options.timeout) || 15000)),
                maximumAge: Math.max(0, Math.min(300000, Number(options && options.maximumAge) || 0))
              });
              let nextWatch = 0;
              const watches = new Map();
              const geolocation = Object.freeze({
                getCurrentPosition(success, error, options) {
                  if (typeof success !== 'function') throw new TypeError('success callback required');
                  locationRequest(options).then(value => success(position(value))).catch(reason => {
                    if (typeof error === 'function') error({code: 1, message: String(reason.message || reason)});
                  });
                },
                watchPosition(success, error, options) {
                  if (typeof success !== 'function') throw new TypeError('success callback required');
                  const watchId = ++nextWatch;
                  const tick = () => {
                    if (!watches.has(watchId)) return;
                    locationRequest(options).then(value => success(position(value))).catch(reason => {
                      if (typeof error === 'function') error({code: 1, message: String(reason.message || reason)});
                    }).finally(() => {
                      if (watches.has(watchId)) watches.set(watchId, setTimeout(tick, 5000));
                    });
                  };
                  watches.set(watchId, setTimeout(tick, 0));
                  return watchId;
                },
                clearWatch(watchId) {
                  const timer = watches.get(Number(watchId));
                  if (timer != null) clearTimeout(timer);
                  watches.delete(Number(watchId));
                }
              });
              try {
                const navigatorPrototype = Object.getPrototypeOf(navigator);
                Object.defineProperty(navigatorPrototype, 'geolocation', {
                  get: () => geolocation, configurable: false, enumerable: true
                });
                Object.defineProperty(navigator, 'geolocation', {
                  value: geolocation, configurable: false, enumerable: true, writable: false
                });
              } catch (_) {}

              const blockedSensors = [
                'Accelerometer', 'Gyroscope', 'Magnetometer', 'LinearAccelerationSensor',
                'GravitySensor', 'AbsoluteOrientationSensor', 'RelativeOrientationSensor',
                'AmbientLightSensor', 'DeviceMotionEvent', 'DeviceOrientationEvent'
              ];
              for (const name of blockedSensors) {
                try { Object.defineProperty(globalThis, name, {value: undefined, configurable: false}); }
                catch (_) {}
              }
              const blockedEvents = new Set([
                'devicemotion', 'deviceorientation', 'deviceorientationabsolute'
              ]);
              const originalAddEventListener = EventTarget.prototype.addEventListener;
              try {
                Object.defineProperty(EventTarget.prototype, 'addEventListener', {
                  configurable: false, writable: false,
                  value: function(type, listener, options) {
                    if (blockedEvents.has(String(type).toLowerCase())) return;
                    return originalAddEventListener.call(this, type, listener, options);
                  }
                });
                for (const property of ['ondevicemotion', 'ondeviceorientation',
                    'ondeviceorientationabsolute']) {
                  Object.defineProperty(globalThis, property, {
                    configurable: false, get: () => null, set: () => {}
                  });
                }
              } catch (_) {}

              try {
                Object.defineProperty(globalThis, 'Notification', {
                  value: undefined, configurable: false, writable: false
                });
                Object.defineProperty(globalThis, 'PushManager', {
                  value: undefined, configurable: false, writable: false
                });
                if (globalThis.ServiceWorkerRegistration) {
                  Object.defineProperty(ServiceWorkerRegistration.prototype, 'pushManager', {
                    get: () => undefined, configurable: false
                  });
                  Object.defineProperty(ServiceWorkerRegistration.prototype, 'showNotification', {
                    value: () => Promise.reject(denied('Notifications are permanently disabled')),
                    configurable: false, writable: false
                  });
                }
              } catch (_) {}
            })();
            """;
}
