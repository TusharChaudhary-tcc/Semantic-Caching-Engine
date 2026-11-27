import { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";
import "./SkyIntro.css";

/**
 * First-visit 3D Blue Sky & Cloud Intro.
 * Scrolling (or clicking "Dive In") zooms the 3D camera directly through the second 'C'
 * ("Semantic [C]ache Engine") and triggers the top-to-bottom dashboard reveal.
 */
export default function SkyIntro({ onProgressChange, onComplete }) {
  const [progress, setProgress] = useState(0);
  const [tilt, setTilt] = useState({ x: 0, y: 0 });
  const [origin, setOrigin] = useState({ x: "50%", y: "50%" });

  const stageRef = useRef(null);
  const targetCRef = useRef(null);
  const progressRef = useRef(0);
  const animFrameRef = useRef(null);
  const completedRef = useRef(false);

  const measurePortalOrigin = useCallback(() => {
    if (!stageRef.current || !targetCRef.current) return;
    const stageRect = stageRef.current.getBoundingClientRect();
    const cRect = targetCRef.current.getBoundingClientRect();
    if (stageRect.width === 0 || stageRect.height === 0) return;

    const cx = cRect.left + cRect.width * 0.48 - stageRect.left;
    const cy = cRect.top + cRect.height * 0.52 - stageRect.top;
    setOrigin({
      x: `${((cx / stageRect.width) * 100).toFixed(2)}%`,
      y: `${((cy / stageRect.height) * 100).toFixed(2)}%`,
    });
  }, []);

  useLayoutEffect(() => {
    measurePortalOrigin();
    window.addEventListener("resize", measurePortalOrigin);
    return () => window.removeEventListener("resize", measurePortalOrigin);
  }, [measurePortalOrigin]);

  const updateProgress = useCallback(
    (next) => {
      const clamped = Math.max(0, Math.min(1, next));
      progressRef.current = clamped;
      setProgress(clamped);
      onProgressChange?.(clamped);

      if (clamped >= 0.995 && !completedRef.current) {
        completedRef.current = true;
        onComplete?.();
      }
    },
    [onComplete, onProgressChange]
  );

  // Smooth wheel + touch + keyboard scroll-through
  useEffect(() => {
    let touchStartY = null;

    const handleWheel = (e) => {
      e.preventDefault();
      const delta = e.deltaY * 0.00085;
      updateProgress(progressRef.current + delta);
    };

    const handleTouchStart = (e) => {
      touchStartY = e.touches[0]?.clientY ?? null;
    };

    const handleTouchMove = (e) => {
      if (touchStartY === null) return;
      const currentY = e.touches[0]?.clientY ?? touchStartY;
      const delta = (touchStartY - currentY) * 0.0025;
      touchStartY = currentY;
      updateProgress(progressRef.current + delta);
    };

    const handleKeyDown = (e) => {
      if (e.key === "ArrowDown" || e.key === "PageDown" || e.key === " ") {
        e.preventDefault();
        updateProgress(progressRef.current + 0.14);
      } else if (e.key === "ArrowUp" || e.key === "PageUp") {
        e.preventDefault();
        updateProgress(progressRef.current - 0.14);
      }
    };

    window.addEventListener("wheel", handleWheel, { passive: false });
    window.addEventListener("touchstart", handleTouchStart, { passive: true });
    window.addEventListener("touchmove", handleTouchMove, { passive: true });
    window.addEventListener("keydown", handleKeyDown);

    return () => {
      window.removeEventListener("wheel", handleWheel);
      window.removeEventListener("touchstart", handleTouchStart);
      window.removeEventListener("touchmove", handleTouchMove);
      window.removeEventListener("keydown", handleKeyDown);
    };
  }, [updateProgress]);

  useEffect(() => {
    return () => {
      if (animFrameRef.current) cancelAnimationFrame(animFrameRef.current);
    };
  }, []);

  const handleMouseMove = (e) => {
    const nx = (e.clientX / window.innerWidth - 0.5) * 2;
    const ny = (e.clientY / window.innerHeight - 0.5) * 2;
    setTilt({ x: nx, y: ny });
  };

  const animateDiveIn = () => {
    if (animFrameRef.current) cancelAnimationFrame(animFrameRef.current);
    const startVal = progressRef.current;
    const startTime = performance.now();
    const duration = 1250;

    const step = (now) => {
      const elapsed = now - startTime;
      const t = Math.min(1, elapsed / duration);
      // Cubic ease-in-out
      const eased = t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
      updateProgress(startVal + (1 - startVal) * eased);
      if (t < 1) {
        animFrameRef.current = requestAnimationFrame(step);
      }
    };
    animFrameRef.current = requestAnimationFrame(step);
  };

  // Exponential zoom curve so the final push through 'C' accelerates naturally
  const zoomScale = 1 + Math.pow(progress, 2.2) * 34;
  const heroOpacity = progress < 0.65 ? 1 : Math.max(0, 1 - (progress - 0.65) / 0.25);
  const skyOpacity = progress < 0.72 ? 1 : Math.max(0, 1 - (progress - 0.72) / 0.28);
  const cloudSpread = 1 + progress * 1.8;

  return (
    <div
      className="sky-intro-overlay"
      style={{ opacity: skyOpacity, pointerEvents: skyOpacity < 0.1 ? "none" : "auto" }}
      onMouseMove={handleMouseMove}
    >
      {/* Volumetric Sunbeams */}
      <div
        className="sky-sunbeams"
        style={{
          transform: `translate3d(${tilt.x * -14}px, ${tilt.y * -10}px, 0)`,
        }}
      />

      {/* Top Navigation Hint */}
      <nav className="sky-nav" style={{ opacity: Math.max(0, 1 - progress * 4) }}>
        <div className="sky-brand-pill">
          <span className="sky-dot" />
          SemanticCache-LLM
        </div>
        <button type="button" className="sky-skip-btn" onClick={animateDiveIn}>
          Enter Engine →
        </button>
      </nav>

      {/* 3D Camera Stage anchored to the second 'C' */}
      <div
        ref={stageRef}
        className="sky-3d-stage"
        style={{
          transformOrigin: `${origin.x} ${origin.y}`,
          transform: `perspective(1200px) scale(${zoomScale}) rotateY(${tilt.x * 4 * (1 - progress)}deg) rotateX(${tilt.y * -3 * (1 - progress)}deg)`,
          opacity: heroOpacity,
        }}
      >
        {/* Distant Cloud Layer */}
        <div
          className="cloud-layer cloud-back"
          style={{
            transform: `translate3d(${tilt.x * -22}px, ${tilt.y * -16}px, -180px) scale(${cloudSpread})`,
          }}
        >
          <div className="cloud cloud-1" />
          <div className="cloud cloud-2" />
          <div className="cloud cloud-3" />
        </div>

        {/* Centerpiece 3D Title floating in the Clouds */}
        <div className="sky-hero-content">
          <p className="sky-kicker" style={{ opacity: Math.max(0, 1 - progress * 3.5) }}>
            TWO-STAGE VECTOR + CROSS-ENCODER ARCHITECTURE
          </p>
          <h1 className="sky-title">
            <span className="word-semantic">Semantic</span>{" "}
            <span className="word-cache">
              <span ref={targetCRef} className="zoom-target-c">
                C
              </span>
              ache
            </span>{" "}
            <span className="word-engine">Engine</span>
          </h1>
          <p className="sky-subtitle" style={{ opacity: Math.max(0, 1 - progress * 3.5) }}>
            Sub-35ms semantic retrieval · ONNX Cross-Encoder intent verification · Cost-weighted eviction
          </p>
        </div>

        {/* Foreground Cloud Layer */}
        <div
          className="cloud-layer cloud-front"
          style={{
            transform: `translate3d(${tilt.x * 30}px, ${tilt.y * 20}px, 120px) scale(${1 + progress * 2.8})`,
            opacity: Math.max(0, 1 - progress * 2.2),
          }}
        >
          <div className="cloud cloud-4" />
          <div className="cloud cloud-5" />
        </div>
      </div>

      {/* Scroll Indicator */}
      <div className="sky-scroll-cue" style={{ opacity: Math.max(0, 1 - progress * 5) }}>
        <div className="scroll-mouse">
          <div className="scroll-wheel" />
        </div>
        <span>Scroll to zoom through the Cache Portal</span>
        <button type="button" className="dive-pill-btn" onClick={animateDiveIn}>
          Dive In ↓
        </button>
      </div>

      {/* Progress Bar at Bottom */}
      <div className="sky-progress-track">
        <div className="sky-progress-fill" style={{ width: `${Math.round(progress * 100)}%` }} />
      </div>
    </div>
  );
}
