const RAY_ANGLES = Array.from({ length: 20 }, (_, index) => index * 18);
export const CINEMATIC_BLACKOUT_MS = 700;

interface NinjaCinematicOverlayProps {
  focus: { x: number; y: number } | null;
  blackout?: boolean;
}

export function NinjaCinematicOverlay({ focus, blackout = false }: NinjaCinematicOverlayProps) {
  return (
    <div
      className={`ninja-cinematic${blackout ? ' ninja-cinematic--blackout' : ''}`}
      style={
        focus
          ? ({
              '--ninja-focus-x': `${focus.x}%`,
              '--ninja-focus-y': `${focus.y}%`,
            } as React.CSSProperties)
          : undefined
      }
      aria-hidden="true"
    >
      <div className="ninja-cinematic__dark" />
      {focus && (
        <>
          <div className="ninja-cinematic__focus" />
          <div className="ninja-cinematic__rays">
            {RAY_ANGLES.map((angle, index) => (
              <span
                key={angle}
                style={
                  {
                    '--ninja-ray-angle': `${angle}deg`,
                    '--ninja-ray-delay': `${(index % 5) * 35}ms`,
                    '--ninja-ray-width': `${index % 3 === 0 ? 5 : index % 2 === 0 ? 3 : 2}px`,
                  } as React.CSSProperties
                }
              />
            ))}
          </div>
        </>
      )}
    </div>
  );
}
