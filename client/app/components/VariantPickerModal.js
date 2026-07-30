"use client";

import { VARIANT_OPTIONS } from "../lib/variants";

export default function VariantPickerModal({
  open,
  selectedVariant,
  onClose,
  onSelect,
}) {
  if (!open) {
    return null;
  }

  return (
    <div className="private-room-modal-backdrop" role="presentation">
      <div
        className="private-room-modal max-w-[40rem]"
        role="dialog"
        aria-modal="true"
        aria-label="Choose variation"
      >
        <button
          className="private-room-close"
          type="button"
          onClick={onClose}
          aria-label="Close variation modal"
        >
          ×
        </button>

        <div className="private-room-modal-copy">
          <p className="private-room-kicker">Teen Patti</p>
          <h2>Choose a game type</h2>
          <p>Pick classic Teen Patti or switch to a public variant with wildcard, lowball, or cycle-based rules.</p>
        </div>

        <div className="grid gap-3">
          {VARIANT_OPTIONS.map((variant) => {
            const active = selectedVariant === variant.id;

            return (
              <button
                key={variant.id}
                type="button"
                onClick={() => onSelect(variant.id)}
                className={[
                  "rounded-[20px] border px-4 py-4 text-left transition",
                  active
                    ? "border-[#ffd778]/45 bg-[linear-gradient(180deg,rgba(102,53,20,0.95),rgba(42,22,11,0.98))] shadow-[0_18px_34px_rgba(0,0,0,0.28)]"
                    : "border-white/10 bg-[linear-gradient(180deg,rgba(39,15,20,0.94),rgba(18,9,14,0.98))] hover:border-[#ffd778]/25"
                ].join(" ")}
              >
                <strong className="block text-lg font-black uppercase tracking-[0.14em] text-[#fff3d7]">
                  {variant.label}
                </strong>
                <span className="mt-1 block text-sm text-white/72">
                  {variant.summary}
                </span>
              </button>
            );
          })}
        </div>
      </div>
    </div>
  );
}
