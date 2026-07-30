import ButtonClickSound from "./components/ButtonClickSound";
import "./globals.css";

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
    <html lang="en">
      <body className="antialiased">
        <ButtonClickSound />
        {children}
      </body>
    </html>
  );
}
