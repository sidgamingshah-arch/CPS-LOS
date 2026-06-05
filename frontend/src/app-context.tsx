import { createContext, useContext } from "react";

export type Nav = (view: string, ref?: string) => void;

export interface AppCtx {
  actor: string;
  notify: (text: string, err?: boolean) => void;
  nav: Nav;
}

export const AppContext = createContext<AppCtx>({
  actor: "demo.user",
  notify: () => {},
  nav: () => {},
});

export const useApp = () => useContext(AppContext);

export const ACTORS = [
  "rm.user",
  "analyst.user",
  "credit.officer",
  "credit.committee",
  "compliance.officer",
  "credit.ops",
  "portfolio.manager",
  "cro",
];
