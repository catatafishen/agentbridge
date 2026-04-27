import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

/**
 * Tests for ChatContainer scroll behavior: wheel-driven autoscroll toggle,
 * touch gesture handling, and bottom-detection logic.
 *
 * happy-dom has no layout engine, so scrollHeight/scrollTop/clientHeight are
 * always 0. We use Object.defineProperty to simulate scroll geometry.
 */

/** Override the scroll geometry properties on an element. */
function mockScrollGeometry(el, {scrollHeight = 1000, scrollTop = 0, clientHeight = 500}) {
    Object.defineProperty(el, 'scrollHeight', {value: scrollHeight, writable: true, configurable: true});
    Object.defineProperty(el, 'scrollTop', {value: scrollTop, writable: true, configurable: true});
    Object.defineProperty(el, 'clientHeight', {value: clientHeight, writable: true, configurable: true});
}

/** Replace requestAnimationFrame with synchronous invocation so rAF callbacks execute immediately. */
function useSyncRAF() {
    const original = globalThis.requestAnimationFrame;
    globalThis.requestAnimationFrame = (cb) => {
        cb(performance.now());
        return 0;
    };
    return () => {
        globalThis.requestAnimationFrame = original;
    };
}

describe('ChatContainer scroll behavior', () => {
    let container;
    let restoreRAF;

    beforeEach(() => {
        document.body.innerHTML = '';
        container = document.createElement('chat-container');
        document.body.appendChild(container);
        restoreRAF = useSyncRAF();
    });

    afterEach(() => {
        vi.useRealTimers();
        restoreRAF();
        vi.restoreAllMocks();
    });

    describe('_isAtBottom (30px tolerance)', () => {
        it('reports at bottom when within 30px', () => {
            mockScrollGeometry(container, {scrollHeight: 1000, scrollTop: 480, clientHeight: 500});
            // scrollHeight - scrollTop - clientHeight = 20 < 30 → at bottom
            expect(container._isAtBottom()).toBe(true);
        });

        it('reports not at bottom when more than 30px away', () => {
            mockScrollGeometry(container, {scrollHeight: 1000, scrollTop: 400, clientHeight: 500});
            // scrollHeight - scrollTop - clientHeight = 100 > 30 → not at bottom
            expect(container._isAtBottom()).toBe(false);
        });

        it('reports at bottom when exactly at bottom (0px gap)', () => {
            mockScrollGeometry(container, {scrollHeight: 1000, scrollTop: 500, clientHeight: 500});
            expect(container._isAtBottom()).toBe(true);
        });

        it('reports at bottom when gap is exactly 30px', () => {
            mockScrollGeometry(container, {scrollHeight: 1000, scrollTop: 470, clientHeight: 500});
            // gap = 30 → 30 < 30 is false, so NOT at bottom
            expect(container._isAtBottom()).toBe(false);
        });

        it('reports at bottom when gap is 29px', () => {
            mockScrollGeometry(container, {scrollHeight: 1000, scrollTop: 471, clientHeight: 500});
            // gap = 29 < 30 → at bottom
            expect(container._isAtBottom()).toBe(true);
        });
    });

    describe('wheel handler', () => {
        it('disables autoscroll on upward scroll (deltaY < 0)', () => {
            const spy = vi.spyOn(globalThis._bridge, 'autoScrollDisabled');
            expect(container.autoScroll).toBe(true);

            container.dispatchEvent(new WheelEvent('wheel', {deltaY: -100}));

            expect(container.autoScroll).toBe(false);
            expect(spy).toHaveBeenCalled();
        });

        it('keeps autoscroll on when scrolling down at bottom (deltaY > 0)', () => {
            const spy = vi.spyOn(globalThis._bridge, 'autoScrollDisabled');
            expect(container.autoScroll).toBe(true);

            container.dispatchEvent(new WheelEvent('wheel', {deltaY: 100}));

            expect(container.autoScroll).toBe(true);
            expect(spy).not.toHaveBeenCalled();
        });

        it('re-enables autoscroll when user scrolls back to bottom', () => {
            const enableSpy = vi.spyOn(globalThis._bridge, 'autoScrollEnabled');

            // First: disable autoscroll
            container.dispatchEvent(new WheelEvent('wheel', {deltaY: -100}));
            expect(container.autoScroll).toBe(false);

            // Simulate being at bottom
            mockScrollGeometry(container, {scrollHeight: 1000, scrollTop: 500, clientHeight: 500});

            // Scroll down — rAF fires synchronously and detects at-bottom
            container.dispatchEvent(new WheelEvent('wheel', {deltaY: 100}));

            expect(container.autoScroll).toBe(true);
            expect(enableSpy).toHaveBeenCalled();
        });

        it('does not re-enable autoscroll when not at bottom', () => {
            const enableSpy = vi.spyOn(globalThis._bridge, 'autoScrollEnabled');

            // Disable autoscroll
            container.dispatchEvent(new WheelEvent('wheel', {deltaY: -100}));
            expect(container.autoScroll).toBe(false);

            // Not at bottom
            mockScrollGeometry(container, {scrollHeight: 1000, scrollTop: 200, clientHeight: 500});

            container.dispatchEvent(new WheelEvent('wheel', {deltaY: 100}));

            expect(container.autoScroll).toBe(false);
            expect(enableSpy).not.toHaveBeenCalled();
        });

        it('ignores deltaY === 0 when autoscroll is on', () => {
            const spy = vi.spyOn(globalThis._bridge, 'autoScrollDisabled');

            container.dispatchEvent(new WheelEvent('wheel', {deltaY: 0}));

            expect(container.autoScroll).toBe(true);
            expect(spy).not.toHaveBeenCalled();
        });
    });

    describe('touch handlers', () => {
        function touchStart(clientY) {
            container.dispatchEvent(new TouchEvent('touchstart', {
                touches: [{clientY, identifier: 0, target: container}],
            }));
        }

        function touchMove(clientY) {
            container.dispatchEvent(new TouchEvent('touchmove', {
                touches: [{clientY, identifier: 0, target: container}],
            }));
        }

        function touchEnd() {
            container.dispatchEvent(new TouchEvent('touchend', {
                touches: [],
                changedTouches: [{clientY: 0, identifier: 0, target: container}],
            }));
        }

        it('disables autoscroll on upward finger drag (>10px threshold)', () => {
            const spy = vi.spyOn(globalThis._bridge, 'autoScrollDisabled');

            touchStart(200);
            // Finger moves down → content scrolls up (user wants to read earlier)
            touchMove(215);

            expect(container.autoScroll).toBe(false);
            expect(spy).toHaveBeenCalled();
        });

        it('ignores small finger movements (<= 10px)', () => {
            const spy = vi.spyOn(globalThis._bridge, 'autoScrollDisabled');

            touchStart(200);
            touchMove(208); // only 8px

            expect(container.autoScroll).toBe(true);
            expect(spy).not.toHaveBeenCalled();
        });

        it('re-enables autoscroll on touchend when at bottom', () => {
            const enableSpy = vi.spyOn(globalThis._bridge, 'autoScrollEnabled');

            // Disable autoscroll via touch
            touchStart(200);
            touchMove(215);
            expect(container.autoScroll).toBe(false);

            // Simulate at bottom
            mockScrollGeometry(container, {scrollHeight: 1000, scrollTop: 500, clientHeight: 500});

            touchEnd();

            expect(container.autoScroll).toBe(true);
            expect(enableSpy).toHaveBeenCalled();
        });

        it('does not re-enable on touchend when not at bottom', () => {
            const enableSpy = vi.spyOn(globalThis._bridge, 'autoScrollEnabled');

            // Disable autoscroll
            touchStart(200);
            touchMove(215);
            expect(container.autoScroll).toBe(false);

            // Not at bottom
            mockScrollGeometry(container, {scrollHeight: 1000, scrollTop: 200, clientHeight: 500});

            touchEnd();

            expect(container.autoScroll).toBe(false);
            expect(enableSpy).not.toHaveBeenCalled();
        });

        it('does not disable on downward finger drag (scroll down)', () => {
            const spy = vi.spyOn(globalThis._bridge, 'autoScrollDisabled');

            touchStart(200);
            // Finger moves up → content scrolls down
            touchMove(185);

            expect(container.autoScroll).toBe(true);
            expect(spy).not.toHaveBeenCalled();
        });
    });

    describe('autoScroll property', () => {
        it('starts enabled after connectedCallback', () => {
            expect(container.autoScroll).toBe(true);
        });

        it('setter sets scrollTop to scrollHeight when enabled', () => {
            mockScrollGeometry(container, {scrollHeight: 2000, scrollTop: 0, clientHeight: 500});

            container.autoScroll = true;

            expect(container.scrollTop).toBe(2000);
        });

        it('setter does not scroll when disabled', () => {
            mockScrollGeometry(container, {scrollHeight: 2000, scrollTop: 500, clientHeight: 500});

            container.autoScroll = false;

            expect(container.scrollTop).toBe(500);
        });
    });

    describe('scrollIfNeeded', () => {
        it('scrolls to bottom when autoScroll is on', () => {
            mockScrollGeometry(container, {scrollHeight: 2000, scrollTop: 0, clientHeight: 500});

            container.scrollIfNeeded();

            expect(container.scrollTop).toBe(2000);
        });

        it('does not scroll when autoScroll is off', () => {
            container.dispatchEvent(new WheelEvent('wheel', {deltaY: -100})); // disable
            mockScrollGeometry(container, {scrollHeight: 2000, scrollTop: 500, clientHeight: 500});

            container.scrollIfNeeded();

            expect(container.scrollTop).toBe(500);
        });

        it('does not scroll during restore mode', () => {
            mockScrollGeometry(container, {scrollHeight: 2000, scrollTop: 300, clientHeight: 500});

            container.pauseAutoScrollForRestore();
            container.scrollIfNeeded();

            expect(container.scrollTop).toBe(300);
        });

        it('scrolls again after restore ends', () => {
            mockScrollGeometry(container, {scrollHeight: 2000, scrollTop: 300, clientHeight: 500});

            container.pauseAutoScrollForRestore();
            container.scrollIfNeeded();
            expect(container.scrollTop).toBe(300);

            container.stopAutoScrollRestore();
            container.scrollIfNeeded();
            expect(container.scrollTop).toBe(2000);
        });
    });

    describe('scroll event handling', () => {
        it('marks active scrolling and clears the marker after scroll idle', () => {
            vi.useFakeTimers();
            const startSpy = vi.spyOn(globalThis._bridge, 'scrollStarted');
            const endSpy = vi.spyOn(globalThis._bridge, 'scrollEnded');

            container.dispatchEvent(new Event('scroll'));

            expect(container.classList.contains('is-scrolling')).toBe(true);
            expect(startSpy).toHaveBeenCalledTimes(1);

            vi.advanceTimersByTime(140);

            expect(container.classList.contains('is-scrolling')).toBe(false);
            expect(endSpy).toHaveBeenCalledTimes(1);
            vi.useRealTimers();
        });

        it('defers load-more clicks out of the scroll event', () => {
            const restoreRAF = globalThis.requestAnimationFrame;
            let queuedRAF = null;
            globalThis.requestAnimationFrame = (cb) => {
                queuedRAF = cb;
                return 1;
            };
            const loadMore = document.createElement('load-more');
            container.messages.appendChild(loadMore);
            const clickSpy = vi.spyOn(loadMore, 'click');
            mockScrollGeometry(container, {scrollHeight: 1000, scrollTop: 10, clientHeight: 500});
            container._prevScrollTop = 50;

            container.dispatchEvent(new Event('scroll'));

            expect(clickSpy).not.toHaveBeenCalled();

            queuedRAF(performance.now());

            expect(clickSpy).toHaveBeenCalledTimes(1);
            globalThis.requestAnimationFrame = restoreRAF;
        });
    });

    describe('instant scroll bypass (stutter prevention)', () => {
        it('scrollIfNeeded forces scroll-behavior to auto even when smooth is set', () => {
            mockScrollGeometry(container, {scrollHeight: 2000, scrollTop: 0, clientHeight: 500});
            container.style.scrollBehavior = 'smooth';

            container.scrollIfNeeded();

            expect(container.scrollTop).toBe(2000);
            // scroll-behavior must be restored to smooth after the instant scroll
            expect(container.style.scrollBehavior).toBe('smooth');
        });

        it('scrollIfNeeded restores empty scroll-behavior when it was unset', () => {
            mockScrollGeometry(container, {scrollHeight: 2000, scrollTop: 0, clientHeight: 500});
            container.style.scrollBehavior = '';

            container.scrollIfNeeded();

            expect(container.scrollTop).toBe(2000);
            expect(container.style.scrollBehavior).toBe('');
        });

        it('compensateScroll forces instant scroll regardless of scroll-behavior', () => {
            mockScrollGeometry(container, {scrollHeight: 2000, scrollTop: 0, clientHeight: 500});
            container.style.scrollBehavior = 'smooth';

            container.compensateScroll(800);

            expect(container.scrollTop).toBe(800);
            expect(container.style.scrollBehavior).toBe('smooth');
        });

        it('forceScroll forces instant scroll regardless of scroll-behavior', () => {
            mockScrollGeometry(container, {scrollHeight: 2000, scrollTop: 0, clientHeight: 500});
            container.style.scrollBehavior = 'smooth';

            container.forceScroll();

            expect(container.scrollTop).toBe(2000);
            // forceScroll temporarily uses auto internally, then restores the previous value
            expect(container.style.scrollBehavior).toBe('smooth');
        });
    });

    describe('disconnectedCallback cleanup', () => {
        it('removes wheel listener after disconnect', () => {
            document.body.removeChild(container);

            const spy = vi.spyOn(globalThis._bridge, 'autoScrollDisabled');
            container.dispatchEvent(new WheelEvent('wheel', {deltaY: -100}));
            expect(spy).not.toHaveBeenCalled();
        });

        it('removes touch listeners after disconnect', () => {
            document.body.removeChild(container);

            const spy = vi.spyOn(globalThis._bridge, 'autoScrollDisabled');
            container.dispatchEvent(new TouchEvent('touchstart', {
                touches: [{clientY: 200, identifier: 0, target: container}],
            }));
            container.dispatchEvent(new TouchEvent('touchmove', {
                touches: [{clientY: 215, identifier: 0, target: container}],
            }));
            expect(spy).not.toHaveBeenCalled();
        });
    });
});
