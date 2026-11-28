import { useCallback, useEffect, useRef, useState } from "react";
import * as THREE from "three";
import { FontLoader } from "three/examples/jsm/loaders/FontLoader.js";
import { TextGeometry } from "three/examples/jsm/geometries/TextGeometry.js";
import helvetikerBoldFont from "../assets/helvetiker_bold.typeface.json";
import "./SkyIntro.css";

function createCloudPuffTexture() {
  const size = 256;
  const canvas = document.createElement("canvas");
  canvas.width = size;
  canvas.height = size;
  const ctx = canvas.getContext("2d");

  const grad = ctx.createRadialGradient(
    size * 0.48,
    size * 0.44,
    size * 0.04,
    size * 0.5,
    size * 0.5,
    size * 0.5
  );
  grad.addColorStop(0, "rgba(255, 255, 255, 0.95)");
  grad.addColorStop(0.38, "rgba(240, 249, 255, 0.72)");
  grad.addColorStop(0.7, "rgba(214, 238, 255, 0.25)");
  grad.addColorStop(1, "rgba(214, 238, 255, 0)");

  ctx.fillStyle = grad;
  ctx.fillRect(0, 0, size, size);

  const texture = new THREE.CanvasTexture(canvas);
  texture.needsUpdate = true;
  return texture;
}

/**
 * True WebGL / Three.js 3D Sky & Cloud Portal Intro.
 * Renders real 3D extruded "Semantic / Caching Engine" text nestled in 3D volumetric clouds,
 * and flies the camera physically through the 'C' in "Caching" on scroll.
 */
export default function SkyIntro({ onProgressChange, onComplete }) {
  const [progress, setProgress] = useState(0);
  const mountRef = useRef(null);
  const targetProgressRef = useRef(0);
  const progressRef = useRef(0);
  const tiltRef = useRef({ x: 0.15, y: -0.08 });
  const animFrameRef = useRef(null);
  const completedRef = useRef(false);

  const updateProgress = useCallback((next) => {
    targetProgressRef.current = Math.max(0, Math.min(1, next));
  }, []);

  // Initialize Three.js WebGL 3D Scene
  useEffect(() => {
    const container = mountRef.current;
    if (!container) return;

    const width = container.clientWidth || window.innerWidth;
    const height = container.clientHeight || window.innerHeight;

    const scene = new THREE.Scene();
    scene.fog = new THREE.FogExp2(0x8ecdf7, 0.032);

    const camera = new THREE.PerspectiveCamera(48, width / height, 0.05, 120);
    const startCamPos = new THREE.Vector3(0, 0.35, 11.8);
    camera.position.copy(startCamPos);

    const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true, powerPreference: "high-performance" });
    renderer.setSize(width, height);
    renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
    renderer.toneMapping = THREE.ACESFilmicToneMapping;
    renderer.toneMappingExposure = 1.12;
    container.appendChild(renderer.domElement);

    // Lighting for strong 3D bevels and depth
    const hemiLight = new THREE.HemisphereLight(0xf0f9ff, 0x075985, 1.35);
    scene.add(hemiLight);

    const sunLight = new THREE.DirectionalLight(0xfffbeb, 2.2);
    sunLight.position.set(8, 11, 9);
    scene.add(sunLight);

    const rimLight = new THREE.DirectionalLight(0x38bdf8, 1.1);
    rimLight.position.set(-8, -4, 4);
    scene.add(rimLight);

    // Parse standard Helvetiker Bold 3D font
    const font = new FontLoader().parse(helvetikerBoldFont);

    const frontMat = new THREE.MeshPhysicalMaterial({
      color: 0xffffff,
      emissive: 0xe0f2fe,
      emissiveIntensity: 0.16,
      roughness: 0.16,
      metalness: 0.05,
      clearcoat: 0.9,
      clearcoatRoughness: 0.1,
    });

    const sideMat = new THREE.MeshStandardMaterial({
      color: 0x38bdf8,
      emissive: 0x0284c7,
      emissiveIntensity: 0.18,
      roughness: 0.3,
      metalness: 0.15,
    });

    const textGroup = new THREE.Group();
    scene.add(textGroup);

    const line1Geo = new TextGeometry("Semantic", {
      font,
      size: 1.45,
      depth: 0.52,
      curveSegments: 16,
      bevelEnabled: true,
      bevelThickness: 0.045,
      bevelSize: 0.018,
      bevelSegments: 4,
    });
    line1Geo.computeBoundingBox();
    const l1Width = line1Geo.boundingBox.max.x - line1Geo.boundingBox.min.x;
    line1Geo.translate(-l1Width / 2, 1.38, 0);
    const line1Mesh = new THREE.Mesh(line1Geo, [frontMat, sideMat]);
    textGroup.add(line1Mesh);

    // Build Line 2 ("Cachin" + "g" + " Engine") so we fly directly through the upper loop of 'g'
    const prefixGeo = new TextGeometry("Cachin", {
      font,
      size: 1.32,
      depth: 0.52,
      curveSegments: 16,
      bevelEnabled: true,
      bevelThickness: 0.045,
      bevelSize: 0.018,
      bevelSegments: 4,
    });
    prefixGeo.computeBoundingBox();
    const prefixWidth = prefixGeo.boundingBox.max.x - prefixGeo.boundingBox.min.x;

    const gGeo = new TextGeometry("g", {
      font,
      size: 1.32,
      depth: 0.62,
      curveSegments: 18,
      bevelEnabled: true,
      bevelThickness: 0.05,
      bevelSize: 0.02,
      bevelSegments: 5,
    });
    gGeo.computeBoundingBox();
    const gWidth = gGeo.boundingBox.max.x - gGeo.boundingBox.min.x;
    const gHeight = gGeo.boundingBox.max.y - gGeo.boundingBox.min.y;
    const gMinY = gGeo.boundingBox.min.y;

    const suffixGeo = new TextGeometry(" Engine", {
      font,
      size: 1.32,
      depth: 0.52,
      curveSegments: 16,
      bevelEnabled: true,
      bevelThickness: 0.045,
      bevelSize: 0.018,
      bevelSegments: 4,
    });
    suffixGeo.computeBoundingBox();
    const suffixWidth = suffixGeo.boundingBox.max.x - suffixGeo.boundingBox.min.x;

    const gap1 = 0.06;
    const gap2 = 0.28;
    const totalLine2Width = prefixWidth + gap1 + gWidth + gap2 + suffixWidth;
    const line2StartX = -totalLine2Width / 2;
    const line2Y = -0.62;

    prefixGeo.translate(line2StartX - prefixGeo.boundingBox.min.x, line2Y, 0);
    const gStartX = line2StartX + prefixWidth + gap1;
    gGeo.translate(gStartX - gGeo.boundingBox.min.x, line2Y, 0);
    suffixGeo.translate(gStartX + gWidth + gap2 - suffixGeo.boundingBox.min.x, line2Y, 0);

    const prefixMesh = new THREE.Mesh(prefixGeo, [frontMat, sideMat]);
    const gMesh = new THREE.Mesh(gGeo, [frontMat, sideMat]);
    const suffixMesh = new THREE.Mesh(suffixGeo, [frontMat, sideMat]);
    textGroup.add(prefixMesh);
    textGroup.add(gMesh);
    textGroup.add(suffixMesh);

    // Compute camera Z so the 3D title covers ~80% of the viewport width
    const maxTextWidth = Math.max(l1Width, totalLine2Width);
    const computeStartCamZ = (w, h) => {
      const halfFovRad = THREE.MathUtils.degToRad(48 / 2);
      const aspect = Math.max(0.5, w / h);
      const idealZ = maxTextWidth / (0.8 * 2 * Math.tan(halfFovRad) * aspect);
      return Math.max(5.5, Math.min(14.5, idealZ));
    };
    startCamPos.z = computeStartCamZ(width, height);
    camera.position.copy(startCamPos);

    // Exact 3D center of the upper loop of 'g'
    const targetPortal = new THREE.Vector3(
      gStartX + gWidth * 0.5,
      line2Y + gMinY + gHeight * 0.64,
      0.3
    );

    // 3D Volumetric Cloud Clusters (matching the 3 cloud banks under "Caching Engine" + foreground smoke)
    const cloudTexture = createCloudPuffTexture();
    const cloudSprites = [];

    const spawnCloudCluster = (cx, cy, cz, count, spreadX, spreadY, spreadZ, baseScale, opacity) => {
      for (let i = 0; i < count; i++) {
        const mat = new THREE.SpriteMaterial({
          map: cloudTexture,
          color: 0xffffff,
          transparent: true,
          opacity: opacity * (0.72 + Math.random() * 0.28),
          depthWrite: false,
        });
        const sprite = new THREE.Sprite(mat);
        const ox = (Math.random() - 0.5) * spreadX;
        const oy = (Math.random() - 0.5) * spreadY;
        const oz = (Math.random() - 0.5) * spreadZ;
        sprite.position.set(cx + ox, cy + oy, cz + oz);
        const s = baseScale * (0.75 + Math.random() * 0.7);
        sprite.scale.set(s * 1.35, s, 1);
        scene.add(sprite);
        cloudSprites.push({
          sprite,
          baseX: sprite.position.x,
          baseY: sprite.position.y,
          speed: 0.15 + Math.random() * 0.25,
          phase: Math.random() * Math.PI * 2,
          baseOpacity: mat.opacity,
        });
      }
    };

    // Three main fluffy clouds cradling "Caching Engine" from behind/below so text stays crisp
    spawnCloudCluster(-4.2, -1.25, -0.85, 18, 3.8, 1.5, 1.0, 2.8, 0.85); // Left cloud under "Cach"
    spawnCloudCluster(0.1, -1.65, -0.75, 20, 4.2, 1.4, 1.0, 3.0, 0.88);  // Center cloud under "ing"
    spawnCloudCluster(4.4, -1.2, -0.85, 18, 3.8, 1.5, 1.0, 2.8, 0.85);   // Right cloud under "Engine"

    // Subtle smoky wisps drifting IN FRONT of the 3D letters (kept light so text pops!)
    spawnCloudCluster(-2.2, -0.25, 1.65, 7, 4.0, 1.4, 0.8, 2.2, 0.18);
    spawnCloudCluster(2.6, -0.35, 1.75, 7, 4.0, 1.4, 0.8, 2.2, 0.16);

    // Distant background sky clouds for depth
    spawnCloudCluster(-6.5, 2.6, -7.0, 12, 5.5, 2.2, 2.0, 4.6, 0.55);
    spawnCloudCluster(6.8, 2.2, -8.5, 12, 5.5, 2.2, 2.0, 4.8, 0.52);

    let rafId = 0;
    const clock = new THREE.Clock();

    const animate = () => {
      rafId = requestAnimationFrame(animate);
      const t = clock.getElapsedTime();

      // Smooth inertia damping (Lenis-style momentum) towards targetProgressRef
      const diff = targetProgressRef.current - progressRef.current;
      if (Math.abs(diff) > 0.0004) {
        const nextP = progressRef.current + diff * 0.1;
        progressRef.current = nextP;
        setProgress(nextP);
        onProgressChange?.(nextP);
        if (nextP >= 0.992 && !completedRef.current) {
          completedRef.current = true;
          onComplete?.();
        }
      }

      const p = progressRef.current;

      // Subtle cloud drift
      for (const item of cloudSprites) {
        item.sprite.position.x = item.baseX + Math.sin(t * item.speed + item.phase) * 0.28;
        item.sprite.position.y = item.baseY + Math.cos(t * item.speed * 0.8 + item.phase) * 0.12;
        // Fade out clouds gently right as camera passes through the portal
        item.sprite.material.opacity = item.baseOpacity * (p < 0.72 ? 1 : Math.max(0, 1 - (p - 0.72) / 0.25));
      }

      // Camera flight: starts in front of whole title, flies straight through the 'C' in Caching
      const easedP = p < 0.5 ? 2 * p * p : 1 - Math.pow(-2 * p + 2, 2) / 2;
      const mouseX = tiltRef.current.x * (1 - easedP) * 1.15;
      const mouseY = tiltRef.current.y * (1 - easedP) * 0.75;

      const camX = THREE.MathUtils.lerp(startCamPos.x + mouseX, targetPortal.x, Math.pow(easedP, 0.85));
      const camY = THREE.MathUtils.lerp(startCamPos.y - mouseY, targetPortal.y, Math.pow(easedP, 0.85));
      // Flies from z=11.8 all the way to z=-1.8 (passing completely through the 'g' loop!)
      const camZ = THREE.MathUtils.lerp(startCamPos.z, -1.8, Math.pow(easedP, 1.15));

      camera.position.set(camX, camY, camZ);

      const lookX = THREE.MathUtils.lerp(0, targetPortal.x, easedP);
      const lookY = THREE.MathUtils.lerp(0.25, targetPortal.y, easedP);
      const lookZ = THREE.MathUtils.lerp(0, -4.0, easedP);
      camera.lookAt(lookX, lookY, lookZ);

      // Gentle 3D float on the text group before dive
      textGroup.rotation.y = Math.sin(t * 0.45) * 0.03 * (1 - easedP) + tiltRef.current.x * 0.08 * (1 - easedP);
      textGroup.rotation.x = Math.cos(t * 0.35) * 0.02 * (1 - easedP) + tiltRef.current.y * 0.05 * (1 - easedP);

      renderer.render(scene, camera);
    };

    animate();

    const handleResize = () => {
      if (!container) return;
      const w = container.clientWidth || window.innerWidth;
      const h = container.clientHeight || window.innerHeight;
      startCamPos.z = computeStartCamZ(w, h);
      camera.aspect = w / h;
      camera.updateProjectionMatrix();
      renderer.setSize(w, h);
    };

    window.addEventListener("resize", handleResize);

    return () => {
      cancelAnimationFrame(rafId);
      window.removeEventListener("resize", handleResize);
      cloudTexture.dispose();
      line1Geo.dispose();
      prefixGeo.dispose();
      gGeo.dispose();
      suffixGeo.dispose();
      frontMat.dispose();
      sideMat.dispose();
      for (const item of cloudSprites) {
        item.sprite.material.dispose();
      }
      renderer.dispose();
      if (renderer.domElement.parentNode === container) {
        container.removeChild(renderer.domElement);
      }
    };
  }, [onComplete, onProgressChange]);

  // Scroll / wheel / touch controls
  useEffect(() => {
    let touchStartY = null;

    const handleWheel = (e) => {
      e.preventDefault();
      const delta = e.deltaY * 0.00095;
      updateProgress(targetProgressRef.current + delta);
    };

    const handleTouchStart = (e) => {
      touchStartY = e.touches[0]?.clientY ?? null;
    };

    const handleTouchMove = (e) => {
      if (touchStartY === null) return;
      const currentY = e.touches[0]?.clientY ?? touchStartY;
      const delta = (touchStartY - currentY) * 0.0028;
      touchStartY = currentY;
      updateProgress(targetProgressRef.current + delta);
    };

    const handleKeyDown = (e) => {
      if (e.key === "ArrowDown" || e.key === "PageDown" || e.key === " ") {
        e.preventDefault();
        updateProgress(targetProgressRef.current + 0.14);
      } else if (e.key === "ArrowUp" || e.key === "PageUp") {
        e.preventDefault();
        updateProgress(targetProgressRef.current - 0.14);
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
    tiltRef.current = {
      x: (e.clientX / window.innerWidth - 0.5) * 2,
      y: (e.clientY / window.innerHeight - 0.5) * 2,
    };
  };

  const animateDiveIn = () => {
    if (animFrameRef.current) cancelAnimationFrame(animFrameRef.current);
    const startVal = progressRef.current;
    const startTime = performance.now();
    const duration = 1450;

    const step = (now) => {
      const elapsed = now - startTime;
      const t = Math.min(1, elapsed / duration);
      const eased = t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
      updateProgress(startVal + (1 - startVal) * eased);
      if (t < 1) {
        animFrameRef.current = requestAnimationFrame(step);
      }
    };
    animFrameRef.current = requestAnimationFrame(step);
  };

  const skyOpacity = progress < 0.72 ? 1 : Math.max(0, 1 - (progress - 0.72) / 0.28);

  return (
    <div
      className="sky-intro-overlay"
      style={{ opacity: skyOpacity, pointerEvents: skyOpacity < 0.08 ? "none" : "auto" }}
      onMouseMove={handleMouseMove}
    >
      {/* Volumetric Sunbeams */}
      <div className="sky-sunbeams" />

      {/* Three.js WebGL 3D Canvas */}
      <div ref={mountRef} className="sky-webgl-mount" />

      {/* Top Navigation */}
      <nav className="sky-nav" style={{ opacity: Math.max(0, 1 - progress * 4) }}>
        <div className="sky-brand-pill">
          <span className="sky-dot" />
          SemanticCache-LLM
        </div>
        <button type="button" className="sky-skip-btn" onClick={animateDiveIn}>
          Enter Engine →
        </button>
      </nav>

      {/* Scroll Indicator */}
      <div className="sky-scroll-cue" style={{ opacity: Math.max(0, 1 - progress * 4.5) }}>
        <div className="scroll-mouse">
          <div className="scroll-wheel" />
        </div>
        <span>Scroll to reach dashboard</span>
        <button type="button" className="dive-pill-btn" onClick={animateDiveIn}>
          Dive In ↓
        </button>
      </div>

      {/* Progress Bar */}
      <div className="sky-progress-track">
        <div className="sky-progress-fill" style={{ width: `${Math.round(progress * 100)}%` }} />
      </div>
    </div>
  );
}
