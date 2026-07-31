import { Manrope, Syne } from "next/font/google";
import ButtonClickSound from "./components/ButtonClickSound";
import "./globals.css";

const manrope = Manrope({
  subsets: ["latin"],
  variable: "--font-manrope",
  display: "swap",
});

const syne = Syne({
  subsets: ["latin"],
  variable: "--font-syne",
  display: "swap",
});

export const metadata = {
  title: "Teen Patti Casino",
  description: "Server-authoritative Teen Patti casino table"
};

export const viewport = {
  width: "device-width",
  initialScale: 1,
  viewportFit: "cover",
};

export default function RootLayout({ children }) {
  return (
    <html lang="en" className={`${manrope.variable} ${syne.variable}`}>
      <body className="antialiased">
        <ButtonClickSound />
        {children}
      </body>
    </html>
  );
}
