import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  output: "standalone",
  async rewrites() {
    return [
      {
        source: "/api/:path*",
        destination: process.env.API_URL 
          ? `${process.env.API_URL}/api/:path*` 
          : "http://localhost:8080/api/:path*", // Default for local dev
      },
      {
        source: "/predict/:path*",
        destination: process.env.API_URL 
          ? `${process.env.API_URL}/predict/:path*` 
          : "http://localhost:8080/predict/:path*",
      },
    ];
  },
};

export default nextConfig;
