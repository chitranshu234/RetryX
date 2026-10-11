interface LogoProps {
  className?: string
}

export function Logo({ className = '' }: LogoProps) {
  return (
    <>
      <img
        src="/logo-light.png"
        alt="RetryX"
        className={`rx-logo-light ${className}`}
      />
      <img
        src="/logo.png"
        alt="RetryX"
        className={`rx-logo-dark ${className}`}
      />
    </>
  )
}
