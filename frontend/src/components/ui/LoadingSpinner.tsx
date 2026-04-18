interface LoadingSpinnerProps {
  className?: string;
}

export function LoadingSpinner({ className = 'w-3.5 h-3.5' }: LoadingSpinnerProps) {
  return <div className={`${className} shrink-0 rounded-full border-2 border-current border-t-transparent animate-spin`} />;
}
