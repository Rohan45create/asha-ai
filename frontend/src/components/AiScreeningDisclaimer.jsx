import { useLanguageStore } from '../stores/languageStore';

const DISCLAIMER_TEXT = {
  en: 'AI-assisted screening, not a diagnosis. Confirm with a clinician.',
  mr: 'AI-सहाय्यित तपासणी, हा वैद्यकीय निदान नाही. डॉक्टरांकडून खात्री करा.',
  hi: 'AI-सहायक स्क्रीनिंग, यह चिकित्सा निदान नहीं है। डॉक्टर से पुष्टि करें।',
};

export default function AiScreeningDisclaimer({
  className = '',
  style = {},
  variant = 'banner', // 'banner' | 'badge' | 'inline'
}) {
  const { language } = useLanguageStore();
  const text = DISCLAIMER_TEXT[language] || DISCLAIMER_TEXT.en;

  if (variant === 'badge') {
    return (
      <span
        className={`inline-flex items-center gap-1 text-[11px] font-semibold px-2 py-0.5 rounded-md bg-[#FEF3C7] text-[#92400E] border border-[#FDE68A] ${className}`}
        style={style}
      >
        <span className="material-symbols-outlined text-[13px] text-[#B45309]">info</span>
        <span>{text}</span>
      </span>
    );
  }

  if (variant === 'inline') {
    return (
      <span
        className={`inline-flex items-center gap-1 text-xs text-[#92400E] font-medium ${className}`}
        style={style}
      >
        <span className="material-symbols-outlined text-[14px] text-[#B45309] align-middle">info</span>
        <span>{text}</span>
      </span>
    );
  }

  return (
    <div
      className={`flex items-center gap-2 p-2.5 rounded-lg bg-[#FEF3C7] border border-[#FDE68A] text-[#92400E] text-xs font-medium ${className}`}
      style={style}
    >
      <span className="material-symbols-outlined text-[17px] text-[#B45309] flex-shrink-0">
        health_and_safety
      </span>
      <span className="leading-tight">{text}</span>
    </div>
  );
}
