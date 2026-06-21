import type { Metadata } from "next";
import { Inter } from "next/font/google";
import "./globals.css";
import Link from "next/link";

const inter = Inter({ subsets: ["latin"] });

export const metadata: Metadata = {
  title: "MolClass V2",
  description: "Next-Generation Machine Learning Bioactivity Predictor",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en" className="dark">
      <body className={`${inter.className} bg-slate-950 text-slate-100 min-h-screen flex flex-col`}>
        {/* Navigation Bar */}
        <nav className="fixed top-0 w-full z-50 bg-slate-900/80 backdrop-blur-md border-b border-slate-800">
          <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
            <div className="flex items-center justify-between h-16">
              <div className="flex items-center space-x-8">
                <Link href="/" className="text-2xl font-bold bg-clip-text text-transparent bg-gradient-to-r from-blue-400 to-emerald-400">
                  MolClass V2
                </Link>
                <div className="hidden md:block">
                  <div className="flex items-baseline space-x-4">
                    <Link href="/upload" className="text-slate-300 hover:text-white px-3 py-2 rounded-md text-sm font-medium transition-colors">Upload</Link>
                    <Link href="/model-creation" className="text-slate-300 hover:text-white px-3 py-2 rounded-md text-sm font-medium transition-colors">Model Creation</Link>
                    <Link href="/structure-search" className="text-slate-300 hover:text-white px-3 py-2 rounded-md text-sm font-medium transition-colors">Structure Search</Link>
                    <Link href="/prediction-list" className="text-slate-300 hover:text-white px-3 py-2 rounded-md text-sm font-medium transition-colors">Prediction List</Link>
                    <Link href="/dataset-review" className="text-slate-300 hover:text-white px-3 py-2 rounded-md text-sm font-medium transition-colors">Dataset Review</Link>
                    <Link href="/details" className="text-slate-300 hover:text-white px-3 py-2 rounded-md text-sm font-medium transition-colors">Details</Link>
                  </div>
                </div>
              </div>
              <div className="flex items-center">
                 <div className="text-sm text-slate-400">Connected: root@localhost.org</div>
              </div>
            </div>
          </div>
        </nav>

        {/* Main Content Area */}
        <main className="flex-grow pt-24 px-4 sm:px-6 lg:px-8 max-w-7xl mx-auto w-full">
          {children}
        </main>
        
        {/* Footer */}
        <footer className="py-6 text-center text-slate-500 text-sm border-t border-slate-800/50 mt-12">
          &copy; {new Date().getFullYear()} MolClass Project. Modernized Interface.
        </footer>
      </body>
    </html>
  );
}
