import { useEffect, useState } from "react";

const LOCATION_CHANGE_EVENT = "teenpatti:locationchange";

function readLocation() {
  if (typeof window === "undefined") {
    return { pathname: "/", search: "" };
  }
  return {
    pathname: window.location.pathname || "/",
    search: window.location.search || "",
  };
}

export function navigatePush(url) {
  if (typeof window === "undefined") {
    return;
  }
  window.history.pushState(null, "", url);
  window.dispatchEvent(new Event(LOCATION_CHANGE_EVENT));
}

export function navigateReplace(url) {
  if (typeof window === "undefined") {
    return;
  }
  window.history.replaceState(null, "", url);
  window.dispatchEvent(new Event(LOCATION_CHANGE_EVENT));
}

export function useAppLocation() {
  const [location, setLocation] = useState(readLocation);

  useEffect(() => {
    const sync = () => setLocation(readLocation());
    window.addEventListener("popstate", sync);
    window.addEventListener(LOCATION_CHANGE_EVENT, sync);
    return () => {
      window.removeEventListener("popstate", sync);
      window.removeEventListener(LOCATION_CHANGE_EVENT, sync);
    };
  }, []);

  return location;
}
