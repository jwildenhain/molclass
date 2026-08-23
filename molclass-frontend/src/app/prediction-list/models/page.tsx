import { redirect } from "next/navigation";

export default function LegacyModelSearchRedirect() {
  redirect("/search?tab=models");
}
