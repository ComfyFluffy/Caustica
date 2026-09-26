"""Independent prefix-integration references; these tests do not execute GPU shaders."""
import unittest
import numpy as np


def build_prefix(edges, sigma, source):
    widths = np.diff(edges)
    segment_t = np.exp(-np.asarray(sigma) * widths)
    transmittance = np.r_[1.,np.cumprod(segment_t)]
    scattering = np.r_[0.,np.cumsum(transmittance[:-1]*(1-segment_t)*source)]
    return scattering,transmittance


def evaluate_prefix(edges, scattering, transmittance, distance):
    distance = np.clip(distance,edges[0],edges[-1])
    cell = min(np.searchsorted(edges,distance,side='right')-1,len(edges)-2)
    fraction = (distance-edges[cell])/(edges[cell+1]-edges[cell])
    if transmittance[cell]<=0: return scattering[cell],transmittance[cell]
    if fraction<=0: return scattering[cell],transmittance[cell]
    if fraction>=1: return scattering[cell+1],transmittance[cell+1]
    ratio = np.clip(transmittance[cell+1]/transmittance[cell],0,1)
    partial = ratio**fraction
    weight = (1-partial)/(1-ratio) if 1-ratio>1e-5 else fraction
    return scattering[cell]+(scattering[cell+1]-scattering[cell])*weight,transmittance[cell]*partial


class FogPrefixTest(unittest.TestCase):
    def test_temporal_reprojection_recovers_previous_relative_point_after_rebase(self):
        rng=np.random.default_rng(9812)
        current_camera=np.array([1048676.25,80.5,-2097100.75])
        previous_camera=current_camera+np.array([-3.25,.5,2.75])
        current_origin=np.array([1048576.,0.,-2097152.])
        previous_origin=np.array([1048064.,0.,-2096640.])
        # Rebase-independent translation reconstructed from two local coordinate domains.
        translation=(current_camera-current_origin)-(previous_camera-previous_origin)+(current_origin-previous_origin)
        np.testing.assert_array_equal(translation,current_camera-previous_camera)
        for _ in range(20):
            rotation,_=np.linalg.qr(rng.normal(size=(3,3)))
            view=np.eye(4);view[:3,:3]=rotation
            f=1/np.tan(rng.uniform(.3,1.))
            projection=np.array([[f/1.7,0,0,0],[0,f,0,0],[0,0,.001,.05],[0,0,-1,0.]])
            previous_clip=projection@view
            shift=np.eye(4);shift[:3,3]=translation
            clip_from_current=(previous_clip@shift).astype(np.float32)
            header_inverse=np.linalg.inv(previous_clip).astype(np.float32)
            point=np.r_[rng.normal(size=3)*30,1].astype(np.float32)
            q=clip_from_current@point
            recovered=header_inverse@q
            recovered=recovered[:3]/recovered[3]
            expected=point[:3]+current_camera-previous_camera
            np.testing.assert_allclose(recovered,expected,rtol=2e-6,atol=2e-5)
            self.assertAlmostEqual(float(np.linalg.norm(recovered)),float(np.linalg.norm(expected)),delta=2e-5)

    def test_quadratic_cell_center_inverse_at_all_supported_extents(self):
        for count in (32,64,512):
            for maximum in (2.56,256,25600):
                edges=maximum*(np.arange(count+1)/count)**2
                centers=(edges[:-1]+edges[1:])/2
                recovered=np.sqrt(centers*count**2/maximum-.25)-.5
                np.testing.assert_allclose(recovered,np.arange(count),atol=1e-12,rtol=0)
                # Interpolation coordinate changes continuously between radial centers.
                probe=np.linspace(centers[0],centers[-1],2000)
                coordinate=np.sqrt(probe*count**2/maximum-.25)-.5
                self.assertTrue(np.all(np.diff(coordinate)>0))

    def test_capped_eight_confidence_has_fifteen_sample_ideal_effective_variance(self):
        alpha=1/8
        weights=alpha*(1-alpha)**np.arange(512)
        self.assertAlmostEqual(float(weights.sum()),1,places=14)
        self.assertAlmostEqual(float(np.sum(weights**2)),1/15,places=14)
        # This variance reduction assumes independent stationary samples, not moving visibility.
        probability=.35
        rng=np.random.default_rng(8439)
        estimate=np.full(20000,probability)
        for _ in range(256):
            estimate=(1-alpha)*estimate+alpha*(rng.random(estimate.size)<probability)
        self.assertAlmostEqual(float(estimate.var()),probability*(1-probability)/15,delta=.0005)

    def test_zero_endpoint_transmittance_preserves_exact_prefix_boundary(self):
        # Fully attenuated right prefix must not turn the exact left endpoint into0^0.
        self.assertEqual(evaluate_prefix([0.,1.],[2.,5.],[.5,0.],0.),(2.,.5))
        self.assertEqual(evaluate_prefix([0.,1.],[2.,5.],[.5,0.],1.),(5.,0.))

    def test_homogeneous_beer_exact_at_arbitrary_depth(self):
        edges = 256*(np.arange(65)/64)**2
        s,t = build_prefix(edges,.031,2.7)
        for depth in np.linspace(0,256,137):
            actual_s,actual_t = evaluate_prefix(edges,s,t,depth)
            self.assertAlmostEqual(actual_t,np.exp(-.031*depth),places=13)
            self.assertAlmostEqual(actual_s,2.7*(1-np.exp(-.031*depth)),places=13)

    def test_vacuum_limit_and_transmittance_monotonicity(self):
        edges = np.array([0.,1.,3.,7.])
        s,t = build_prefix(edges,0,4)
        self.assertEqual(evaluate_prefix(edges,s,t,2),(0,1))
        # A stored source increment with unit T has the finite linear interpolation limit.
        self.assertEqual(evaluate_prefix([0.,2.],[0.,6.],[1.,1.],.5),(1.5,1.))
        s,t = build_prefix(edges,np.array([.3,0,.7]),1)
        values = np.array([evaluate_prefix(edges,s,t,d) for d in np.linspace(0,7,1001)])
        self.assertTrue(np.all(np.diff(values[:,1])<=1e-15))
        self.assertTrue(np.all(np.diff(values[:,0])>=-1e-15))

    def test_current_depth_evaluation_preserves_where_light_was_integrated(self):
        edges = np.array([0.,2.,4.,8.]); s,t = build_prefix(edges,.2,np.array([0.,0.,1.]))
        self.assertEqual(evaluate_prefix(edges,s,t,3)[0],0)
        self.assertGreater(evaluate_prefix(edges,s,t,6)[0],0)
        # Endpoint rescaling incorrectly invents scattering before the illuminated interval.
        rescaled = s[-1]*(1-np.exp(-.2*3))/(1-t[-1])
        self.assertGreater(rescaled,0)

    def test_parallel_segments_and_batched_accumulation_match_serial_float32_integration(self):
        rng=np.random.default_rng(4417)
        steps=64
        sigma=rng.uniform(0,.05,steps).astype(np.float32)
        sigma[rng.random(steps)<.3]=0 # Empty and clipped cells.
        length=rng.uniform(0,8,steps).astype(np.float32)
        lighting=rng.uniform(0,3,(steps,3)).astype(np.float32)
        exposure=np.float32(.37)
        # Serial reference: one invocation walks every step and composes as it goes.
        scattering,transmittance=np.zeros(3,np.float32),np.float32(1)
        serial=[]
        for i in range(steps):
            if sigma[i]!=0:
                segment_t=np.exp(-sigma[i]*length[i])
                scattering=scattering+transmittance*(1-segment_t)*(lighting[i]*exposure)*.95
                transmittance=transmittance*segment_t
            serial.append(np.r_[scattering,transmittance])
        # Integration stores each step's source and transmittance independently; empty cells store (0, 1).
        segments=[np.r_[lighting[i]*exposure,np.exp(-sigma[i]*length[i])] if sigma[i]!=0
                  else np.array([0,0,0,1],np.float32) for i in range(steps)]
        for batch in (1,8,24,64):
            prefix=[np.array([0,0,0,1],np.float32)]
            for first in range(0,steps,batch):
                scattering,transmittance=prefix[first][:3],prefix[first][3]
                for segment in segments[first:first+batch]:
                    scattering=scattering+transmittance*(1-segment[3])*segment[:3]*.95
                    transmittance=transmittance*segment[3]
                    prefix.append(np.r_[scattering,transmittance])
            np.testing.assert_array_equal(np.array(prefix[1:]),np.array(serial))

    def test_dense_shadow_reference_exposes_nonzero_coarse_error(self):
        sigma=.04; start,end=7.,9.
        exact=np.exp(-sigma*start)-np.exp(-sigma*end)
        errors=[]
        for count in (64,512):
            edges=256*(np.arange(count+1)/count)**2
            mid=(edges[:-1]+edges[1:])/2
            visibility=((mid>=start)&(mid<end)).astype(float)
            s,t=build_prefix(edges,sigma,visibility)
            value=evaluate_prefix(edges,s,t,14)[0]
            self.assertGreater(value,0) # Both report no hole; this does not establish accuracy.
            errors.append(abs(value-exact))
        self.assertGreater(errors[0],.005)
        self.assertLess(errors[1],errors[0]/3)


if __name__=='__main__':unittest.main()
