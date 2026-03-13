import { BrowserRouter, Routes, Route } from 'react-router-dom'

export function App() {
  return (
    <BrowserRouter>
      <div className="min-h-screen bg-background">
        <Routes>
          <Route path="/" element={<div className="p-4">SmanWeb - Loading...</div>} />
        </Routes>
      </div>
    </BrowserRouter>
  )
}
