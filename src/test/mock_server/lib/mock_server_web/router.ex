defmodule MockServerWeb.Router do
  use MockServerWeb, :router

  pipeline :api do
    plug :accepts, ["json"]
  end

  scope "/api", MockServerWeb do
    pipe_through :api
  end
end
